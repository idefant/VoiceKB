package com.idefant.voicekb;

import android.app.Application;

public class VoiceKBApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        VoiceKBUtils.applyApplicationLocale(this);
    }
}
