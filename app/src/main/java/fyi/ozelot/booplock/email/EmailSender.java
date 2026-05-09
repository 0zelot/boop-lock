package fyi.ozelot.booplock.email;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import fyi.ozelot.booplock.data.Prefs;

public final class EmailSender {

    private static final int SMTP_SSL_PORT = 465;
    private static final int TIMEOUT_MS = 30_000;
    private static final byte[] MIME_LINE_BREAK =
            new byte[] { '\r', '\n' };

    private EmailSender() {
    }

    public static Result send(Config config, String subject, String body,
                              List<Attachment> attachments) {
        if (!config.isComplete()) {
            return Result.failure("Email settings are incomplete.");
        }

        SmtpSession session = null;
        try {
            session = SmtpSession.connect(config);
            List<Attachment> existingAttachments = filterExistingAttachments(attachments);
            Reply dataReply = session.sendMessage(config, subject, body, existingAttachments);
            return Result.success("SMTP " + dataReply.code + ": " + dataReply.message);
        } catch (Exception e) {
            return Result.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (session != null) {
                session.closeQuietly();
            }
        }
    }

    private static List<Attachment> filterExistingAttachments(List<Attachment> attachments) {
        List<Attachment> out = new ArrayList<>();
        if (attachments == null) return out;
        for (Attachment a : attachments) {
            if (a != null && a.file.exists() && a.file.isFile()) {
                out.add(a);
            }
        }
        return out;
    }

    public static final class Config {
        public final String host;
        public final int port;
        public final String from;
        public final String password;
        public final String to;

        public Config(String host, int port, String from, String password, String to) {
            this.host = clean(host);
            this.port = port;
            this.from = clean(from);
            this.password = password != null ? password : "";
            this.to = clean(to);
        }

        public static Config fromPrefs(Prefs prefs) {
            return new Config(
                    prefs.getEmailSmtpHost(),
                    prefs.getEmailSmtpPort(),
                    prefs.getEmailFrom(),
                    prefs.getEmailPassword(),
                    prefs.getEmailTo());
        }

        public boolean isComplete() {
            return !host.isEmpty()
                    && port > 0
                    && port <= 65535
                    && !from.isEmpty()
                    && !password.isEmpty()
                    && !to.isEmpty();
        }
    }

    public static final class Attachment {
        public final File file;
        public final String name;

        public Attachment(File file, String name) {
            this.file = file;
            this.name = clean(name).isEmpty() ? file.getName() : clean(name);
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Result success(String message) {
            return new Result(true, message);
        }

        public static Result failure(String message) {
            return new Result(false, message);
        }
    }

    private static final class SmtpSession implements Closeable {
        private final Config config;
        private Socket socket;
        private BufferedReader reader;
        private BufferedOutputStream writer;

        static SmtpSession connect(Config config) throws IOException {
            SmtpSession session = new SmtpSession(config);
            session.open();
            return session;
        }

        private SmtpSession(Config config) {
            this.config = config;
        }

        private void open() throws IOException {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(config.host, config.port), TIMEOUT_MS);
            raw.setSoTimeout(TIMEOUT_MS);

            if (config.port == SMTP_SSL_PORT) {
                socket = wrapTls(raw);
            } else {
                socket = raw;
            }
            bindStreams();
            expect(readReply(), 220);

            expect(command("EHLO booplock.local"), 250);
            if (config.port != SMTP_SSL_PORT) {
                expect(command("STARTTLS"), 220);
                socket = wrapTls(socket);
                bindStreams();
                expect(command("EHLO booplock.local"), 250);
            }

            expect(command("AUTH LOGIN"), 334);
            expect(command(base64(config.from)), 334);
            expect(command(base64(config.password)), 235);
        }

        private Socket wrapTls(Socket plain) throws IOException {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(
                    plain, config.host, config.port, true);
            ssl.setSoTimeout(TIMEOUT_MS);
            ssl.startHandshake();
            return ssl;
        }

        private void bindStreams() throws IOException {
            reader = new BufferedReader(new InputStreamReader(
                    new BufferedInputStream(socket.getInputStream()), StandardCharsets.US_ASCII));
            writer = new BufferedOutputStream(socket.getOutputStream());
        }

        Reply sendMessage(Config config, String subject, String body,
                          List<Attachment> attachments) throws IOException {
            expect(command("MAIL FROM:<" + smtpAddress(config.from) + ">"), 250);
            expect(command("RCPT TO:<" + smtpAddress(config.to) + ">"), 250, 251);
            expect(command("DATA"), 354);

            writeMimeMessage(config, subject, body, attachments);
            writeAscii("\r\n.\r\n");
            writer.flush();
            return expect(readReply(), 250);
        }

        private void writeMimeMessage(Config config, String subject, String body,
                                      List<Attachment> attachments) throws IOException {
            String boundary = "BoopLock-" + System.currentTimeMillis();

            writeAscii("Date: " + smtpDate() + "\r\n");
            writeAscii("From: <" + smtpAddress(config.from) + ">\r\n");
            writeAscii("To: <" + smtpAddress(config.to) + ">\r\n");
            writeAscii("Subject: " + encodedHeader(subject) + "\r\n");
            writeAscii("MIME-Version: 1.0\r\n");
            writeAscii("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n");
            writeAscii("\r\n");

            writeAscii("--" + boundary + "\r\n");
            writeAscii("Content-Type: text/plain; charset=UTF-8\r\n");
            writeAscii("Content-Transfer-Encoding: base64\r\n");
            writeAscii("\r\n");
            writeBase64(body.getBytes(StandardCharsets.UTF_8));
            writeAscii("\r\n");

            for (Attachment attachment : attachments) {
                writeAttachment(boundary, attachment);
            }

            writeAscii("--" + boundary + "--\r\n");
        }

        private void writeAttachment(String boundary, Attachment attachment) throws IOException {
            String contentType = URLConnection.guessContentTypeFromName(attachment.name);
            if (contentType == null) contentType = "application/octet-stream";
            String safeName = quoted(attachment.name);

            writeAscii("--" + boundary + "\r\n");
            writeAscii("Content-Type: " + contentType + "; name=\"" + safeName + "\"\r\n");
            writeAscii("Content-Transfer-Encoding: base64\r\n");
            writeAscii("Content-Disposition: attachment; filename=\"" + safeName + "\"\r\n");
            writeAscii("\r\n");

            try (InputStream in = new FileInputStream(attachment.file);
                 OutputStream encoded = Base64.getMimeEncoder(76, MIME_LINE_BREAK)
                         .wrap(new NonClosingOutputStream(writer))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    encoded.write(buffer, 0, read);
                }
            }
            writeAscii("\r\n");
        }

        private Reply command(String line) throws IOException {
            writeAscii(line + "\r\n");
            writer.flush();
            return readReply();
        }

        private Reply readReply() throws IOException {
            StringBuilder message = new StringBuilder();
            int code = -1;
            String line;
            do {
                line = reader.readLine();
                if (line == null) {
                    throw new IOException("SMTP server closed the connection.");
                }
                if (line.length() >= 3) {
                    try {
                        code = Integer.parseInt(line.substring(0, 3));
                    } catch (NumberFormatException ignored) {
                        code = -1;
                    }
                }
                if (message.length() > 0) message.append('\n');
                message.append(line);
            } while (line.length() > 3 && line.charAt(3) == '-');
            return new Reply(code, message.toString());
        }

        private Reply expect(Reply reply, int... allowedCodes) throws IOException {
            for (int allowed : allowedCodes) {
                if (reply.code == allowed) return reply;
            }
            throw new IOException("Unexpected SMTP response: " + reply.message);
        }

        private void writeBase64(byte[] data) throws IOException {
            writer.write(Base64.getMimeEncoder(76, MIME_LINE_BREAK).encode(data));
            writer.write(MIME_LINE_BREAK);
        }

        private void writeAscii(String value) throws IOException {
            writer.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws IOException {
            try {
                if (writer != null && socket != null && !socket.isClosed()) {
                    command("QUIT");
                }
            } finally {
                if (socket != null) socket.close();
            }
        }

        void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
                // Nothing useful to recover after the send attempt is complete.
            }
        }
    }

    private static final class Reply {
        final int code;
        final String message;

        Reply(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;

        NonClosingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodedHeader(String value) {
        return "=?UTF-8?B?" + base64(clean(value)) + "?=";
    }

    private static String smtpDate() {
        return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                .format(new Date());
    }

    private static String smtpAddress(String value) {
        return clean(value).replace("\r", "").replace("\n", "");
    }

    private static String quoted(String value) {
        return clean(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
