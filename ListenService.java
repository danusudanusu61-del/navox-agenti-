package com.navox.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ListenService extends Service implements RecognitionListener {
    static boolean running = false;
    static final Pattern WAKE = Pattern.compile("\\b(hey|hi|hello|ok|okay)\\s+(nav|navv|navox|naav|nova|knox|knave|nave)\\b");
    SpeechRecognizer sr;
    Handler h = new Handler(Looper.getMainLooper());
    PowerManager.WakeLock wl;
    boolean commandMode = false;
    boolean dead = false;
    Runnable again = new Runnable() {
        public void run() {
            listen();
        }
    };

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        startFg();
        running = true;
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "navox:listen");
            wl.acquire();
        }
        next(100);
        return START_STICKY;
    }

    void startFg() {
        String ch = "navox_listen";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "NAVOX listening", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, ch);
        else b = new Notification.Builder(this);
        b.setContentTitle("NAVOX is listening");
        b.setContentText("Say Hey Nav, then your command");
        b.setSmallIcon(R.drawable.ic_notif);
        b.setContentIntent(pi);
        b.setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
    }

    void next(long ms) {
        h.removeCallbacks(again);
        h.postDelayed(again, ms);
    }

    void listen() {
        if (dead) return;
        try {
            if (sr != null) sr.destroy();
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(this);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSharedPreferences("navox", 0).getString("lang", "en-IN"));
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            sr.startListening(i);
        } catch (Exception e) {
            next(3000);
        }
    }

    void beep() {
        try {
            new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception e) { }
    }

    void fire(String cmd) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("cmd", cmd);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) { }
        next(6000);
    }

    @Override
    public void onResults(Bundle b) {
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty()) {
            next(250);
            return;
        }
        if (commandMode) {
            commandMode = false;
            fire(l.get(0).toLowerCase());
            return;
        }
        for (String a : l) {
            String t = a.toLowerCase();
            Matcher m = WAKE.matcher(t);
            if (m.find()) {
                String rest = t.substring(m.end()).trim();
                if (rest.length() > 1) {
                    fire(rest);
                } else {
                    commandMode = true;
                    beep();
                    next(200);
                }
                return;
            }
        }
        next(200);
    }

    @Override
    public void onError(int e) {
        commandMode = false;
        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            stopSelf();
            return;
        }
        if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) next(2000);
        else if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) next(250);
        else next(1500);
    }

    @Override public void onReadyForSpeech(Bundle p) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float v) { }
    @Override public void onBufferReceived(byte[] b) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle b) { }
    @Override public void onEvent(int t, Bundle b) { }

    @Override
    public void onDestroy() {
        dead = true;
        running = false;
        h.removeCallbacks(again);
        try {
            if (sr != null) sr.destroy();
        } catch (Exception e) { }
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
