package fyi.ozelot.booplock;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

public class App extends Application {

    private int startedCount = 0;
    private boolean authenticated = false;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}

            @Override
            public void onActivityStarted(Activity a) {
                startedCount++;
            }

            @Override
            public void onActivityStopped(Activity a) {
                if (--startedCount == 0) {
                    authenticated = false;
                }
            }
        });
    }

    public static App get(Context context) {
        return (App) context.getApplicationContext();
    }

    public boolean isAuthenticated() { return authenticated; }
    public void setAuthenticated(boolean v) { authenticated = v; }
}
