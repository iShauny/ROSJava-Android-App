/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.ros.android;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.common.base.Preconditions;

import org.ros.RosCore;
import org.ros.concurrent.ListenerGroup;
import org.ros.concurrent.SignalRunnable;
import org.ros.exception.RosRuntimeException;
import org.ros.node.DefaultNodeMainExecutor;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeListener;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;

import java.net.URI;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
public class NodeMainExecutorService extends Service implements NodeMainExecutor {

    private static final String TAG = "NodeMainExecutorService";

    // NOTE(damonkohler): If this is 0, the notification does not show up.
    private static final int ONGOING_NOTIFICATION = 1;

    static final String ACTION_START = "org.ros.android.ACTION_START_NODE_RUNNER_SERVICE";
    static final String ACTION_SHUTDOWN = "org.ros.android.ACTION_SHUTDOWN_NODE_RUNNER_SERVICE";
    static final String EXTRA_NOTIFICATION_TITLE = "org.ros.android.EXTRA_NOTIFICATION_TITLE";
    static final String EXTRA_NOTIFICATION_TICKER = "org.ros.android.EXTRA_NOTIFICATION_TICKER";

    private final NodeMainExecutor nodeMainExecutor;
    private final IBinder binder;
    private final ListenerGroup<NodeMainExecutorServiceListener> listeners;

    private Handler handler;
    private WakeLock wakeLock;
    private WifiLock wifiLock;
    private RosCore rosCore;
    private URI masterUri;
    private String rosHostname;

    /**
     * Class for clients to access. Because we know this service always runs in
     * the same process as its clients, we don't need to deal with IPC.
     */
    class LocalBinder extends Binder {
        NodeMainExecutorService getService() {
            return NodeMainExecutorService.this;
        }
    }

    public NodeMainExecutorService() {
        super();
        rosHostname = null;
        nodeMainExecutor = DefaultNodeMainExecutor.newDefault();
        binder = new LocalBinder();
        listeners =
                new ListenerGroup<>(
                        nodeMainExecutor.getScheduledExecutorService());
    }

    @SuppressLint("InvalidWakeLockTag")
    @Override
    public void onCreate() {
        handler = new Handler();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();
        int wifiLockType = WifiManager.WIFI_MODE_FULL;
        try {
            wifiLockType = WifiManager.class.getField("WIFI_MODE_FULL_HIGH_PERF").getInt(null);
        } catch (Exception e) {
            // We must be running on a pre-Honeycomb device.
            Log.w(TAG, "Unable to acquire high performance wifi lock.");
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wifiManager.createWifiLock(wifiLockType, TAG);
        wifiLock.acquire();
    }

    @Override
    public void execute(NodeMain nodeMain, NodeConfiguration nodeConfiguration,
                        Collection<NodeListener> nodeListeneners) {
        nodeMainExecutor.execute(nodeMain, nodeConfiguration, nodeListeneners);
    }

    @Override
    public void execute(NodeMain nodeMain, NodeConfiguration nodeConfiguration) {
        execute(nodeMain, nodeConfiguration, null);
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
        return nodeMainExecutor.getScheduledExecutorService();
    }

    @Override
    public void shutdownNodeMain(NodeMain nodeMain) {
        nodeMainExecutor.shutdownNodeMain(nodeMain);
    }

    // (shaun) Alert dialogs outside of the current window is not permitted in new versions
    // of android, so removed the confirmation dialog to prevent conflicts
    @Override
    public void shutdown(){
        handler.post(new Runnable() {
            @Override
            public void run() {
                signalOnShutdown();
                stopForeground(true);
                stopSelf();
            }
        });
    }

    public void addListener(NodeMainExecutorServiceListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NodeMainExecutorServiceListener listener)
    {
        listeners.remove(listener);
    }

    private void signalOnShutdown() {
        listeners.signal(new SignalRunnable<NodeMainExecutorServiceListener>() {
            @Override
            public void run(NodeMainExecutorServiceListener nodeMainExecutorServiceListener) {
                nodeMainExecutorServiceListener.onShutdown(NodeMainExecutorService.this);
            }
        });
    }

    @Override
    public void onDestroy() {
        toast(getString(R.string.SHUTDOWN_TOAST_CONTENT));
        nodeMainExecutor.shutdown();
        if (rosCore != null) {
            rosCore.shutdown();
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();
    }

    /* (shaun) The next code below controls the notification for the application.
        Since oreo, the method used for creating notifications has changed and you must specify
        a channel id in order to create a notification. Therefore, a block of code underneath a
        version check has been added which creates a channel for the notification and constructs
        the notification. Otherwise, the default notification construction method is used.
    */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        if (intent.getAction().equals(ACTION_START)) {
            Preconditions.checkArgument(intent.hasExtra(EXTRA_NOTIFICATION_TICKER));
            Preconditions.checkArgument(intent.hasExtra(EXTRA_NOTIFICATION_TITLE));
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel("ros_java",
                        getString(R.string.NOTIFICATION_CHANNEL_NAME),
                        NotificationManager.IMPORTANCE_MIN);
                NotificationManager nManager = (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE);
                nManager.createNotificationChannel(channel);
                Notification.Builder builder = new Notification.Builder(this,
                        "ros_java");
                Intent notificationIntent = new Intent(this,
                        NodeMainExecutorService.class);
                notificationIntent.setAction(NodeMainExecutorService.ACTION_SHUTDOWN);
                PendingIntent pendingIntent = PendingIntent.getService(this,
                        0, notificationIntent, 0);
                Notification notification =
                        builder.setContentIntent(pendingIntent)
                                .setSmallIcon(R.mipmap.icon)
                                .setTicker(intent.getStringExtra(EXTRA_NOTIFICATION_TICKER))
                                .setWhen(System.currentTimeMillis())
                                .setContentTitle(intent.getStringExtra(EXTRA_NOTIFICATION_TITLE))
                                .setAutoCancel(true)
                                .setContentText(getString(R.string.NOTIFICATION_TEXT))
                                .setOngoing(true)
                                .build();
                nManager.notify(1, notification);
            }
            else
            {
                Preconditions.checkArgument(intent.hasExtra(EXTRA_NOTIFICATION_TICKER));
                Preconditions.checkArgument(intent.hasExtra(EXTRA_NOTIFICATION_TITLE));
                NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
                Intent notificationIntent = new Intent(this,
                        NodeMainExecutorService.class);
                notificationIntent.setAction(NodeMainExecutorService.ACTION_SHUTDOWN);
                PendingIntent pendingIntent = PendingIntent.getService(this,
                        0, notificationIntent, 0);
                Notification notification = builder.setContentIntent(pendingIntent)
                        .setSmallIcon(R.mipmap.icon)
                        .setTicker(intent.getStringExtra(EXTRA_NOTIFICATION_TICKER))
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(intent.getStringExtra(EXTRA_NOTIFICATION_TITLE))
                        .setAutoCancel(true)
                        .setContentText("Tap to shutdown.")
                        .build();
                startForeground(ONGOING_NOTIFICATION, notification);
            }
        }
        if (intent.getAction().equals(ACTION_SHUTDOWN)) {
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public URI getMasterUri() {
        return masterUri;
    }

    public void setMasterUri(URI uri) {
        masterUri = uri;
    }

    public void setRosHostname(String hostname) {
        rosHostname = hostname;
    }

    public String getRosHostname() {
        return rosHostname;
    }
    /**
     * This version of startMaster can only create private masters.
     *
     * @deprecated use {@link public void startMaster(Boolean isPrivate)} instead.
     */
    @Deprecated
    public void startMaster() {
        startMaster(true);
    }

    /**
     * Starts a new ros master in an AsyncTask.
     * @param isPrivate
     */
    public void startMaster(boolean isPrivate) {
        @SuppressLint("StaticFieldLeak") AsyncTask<Boolean, Void, URI> task = new AsyncTask<Boolean, Void, URI>() {
            @Override
            protected URI doInBackground(Boolean[] params) {
                NodeMainExecutorService.this.startMasterBlocking(params[0]);
                return NodeMainExecutorService.this.getMasterUri();
            }
        };
        task.execute(isPrivate);
        try {
            task.get();
        } catch (InterruptedException e) {
            throw new RosRuntimeException(e);
        } catch (ExecutionException e) {
            throw new RosRuntimeException(e);
        }
    }

    /**
     * Private blocking method to start a Ros Master.
     * @param isPrivate
     */
    private void startMasterBlocking(boolean isPrivate) {
        if (isPrivate) {
            rosCore = RosCore.newPrivate();
        } else if (rosHostname != null) {
            rosCore = RosCore.newPublic(rosHostname, 11311);
        } else {
            rosCore = RosCore.newPublic(11311);
        }
        rosCore.start();
        try {
            rosCore.awaitStart();
        } catch (Exception e) {
            throw new RosRuntimeException(e);
        }
        masterUri = rosCore.getUri();
    }

    public void toast(final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NodeMainExecutorService.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }
}