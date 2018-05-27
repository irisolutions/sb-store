package org.fdroid.fdroid.installer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.fdroid.fdroid.AppDetails;
import org.fdroid.fdroid.Hasher;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.compat.PackageManagerCompat;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.AppProvider;
import org.fdroid.fdroid.data.Schema;
import org.fdroid.fdroid.iris.net.PushAppStatusToServer;
import org.fdroid.fdroid.net.Downloader;
import org.fdroid.fdroid.net.DownloaderService;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manages the whole process when a background update triggers an install or the user
 * requests an APK to be installed.  It handles checking whether the APK is cached,
 * downloading it, putting up and maintaining a {@link Notification}, and more.
 * <p>
 * The {@link App} and {@link Apk} instances are sent via
 * {@link Intent#putExtra(String, android.os.Bundle)}
 * so that Android handles the message queuing and {@link Service} lifecycle for us.
 * For example, if this {@code InstallManagerService} gets killed, Android will cache
 * and then redeliver the {@link Intent} for us, which includes all of the data needed
 * for {@code InstallManagerService} to do its job for the whole lifecycle of an install.
 * <p>
 * The full URL for the APK file to download is also used as the unique ID to
 * represent the download itself throughout F-Droid.  This follows the model
 * of {@link Intent#setData(Uri)}, where the core data of an {@code Intent} is
 * a {@code Uri}.  The full download URL is guaranteed to be unique since it
 * points to files on a filesystem, where there cannot be multiple files with
 * the same name.  This provides a unique ID beyond just {@code packageName}
 * and {@code versionCode} since there could be different copies of the same
 * APK on different servers, signed by different keys, or even different builds.
 * <p><ul>
 * <li>for a {@link Uri} ID, use {@code Uri}, {@link Intent#getData()}
 * <li>for a {@code String} ID, use {@code urlString}, {@link Uri#toString()}, or
 * {@link Intent#getDataString()}
 * <li>for an {@code int} ID, use {@link String#hashCode()} or {@link Uri#hashCode()}
 * </ul></p>
 * The implementations of {@link Uri#toString()} and {@link Intent#getDataString()} both
 * include caching of the generated {@code String}, so it should be plenty fast.
 * <p>
 * This also handles downloading OBB "APK Extension" files for any APK that has one
 * assigned to it.  OBB files are queued up for download before the APK so that they
 * are hopefully in place before the APK starts.  That is not guaranteed though.
 *
 * @see <a href="https://developer.android.com/google/play/expansion-files.html">APK Expansion Files</a>
 */
public class InstallManagerService extends Service {
    private static final String TAG = "InstallManagerService";

    private static final String ACTION_INSTALL = "org.fdroid.fdroid.installer.action.INSTALL";
    private static final String ACTION_CANCEL = "org.fdroid.fdroid.installer.action.CANCEL";

    private static final String EXTRA_APP = "org.fdroid.fdroid.installer.extra.APP";
    private static final String EXTRA_APK = "org.fdroid.fdroid.installer.extra.APK";

    /**
     * The collection of {@link Apk}s that are actively going through this whole process,
     * matching the {@link App}s in {@code ACTIVE_APPS}. The key is the download URL, as
     * in {@link Apk#getUrl()} or {@code urlString}.
     */
    private static final HashMap<String, Apk> ACTIVE_APKS = new HashMap<>(3);

    /**
     * The collection of {@link App}s that are actively going through this whole process,
     * matching the {@link Apk}s in {@code ACTIVE_APKS}. The key is the
     * {@code packageName} of the app.
     */
    private static final HashMap<String, App> ACTIVE_APPS = new HashMap<>(3);
    public static final String IRIS_STORE = "Iris Store";

    private LocalBroadcastManager localBroadcastManager;
    private NotificationManager notificationManager;

    /**
     * This service does not use binding, so no need to implement this method
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debugLog(TAG, "creating Service");
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String packageName = intent.getData().getSchemeSpecificPart();
                for (Map.Entry<String, Apk> entry : ACTIVE_APKS.entrySet()) {
                    if (TextUtils.equals(packageName, entry.getValue().packageName)) {
                        String urlString = entry.getKey();
                        cancelNotification(urlString);
                        break;
                    }
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addDataScheme("package");
        registerReceiver(br, intentFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Utils.debugLog(TAG, "onStartCommand " + intent);

        String urlString = intent.getDataString();
        if (TextUtils.isEmpty(urlString)) {
            Utils.debugLog(TAG, "empty urlString, nothing to do");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            DownloaderService.cancel(this, urlString);
            Apk apk = getApkFromActive(urlString);
            DownloaderService.cancel(this, apk.getPatchObbUrl());
            DownloaderService.cancel(this, apk.getMainObbUrl());
            cancelNotification(urlString);
            return START_NOT_STICKY;
        } else if (!ACTION_INSTALL.equals(action)) {
            Utils.debugLog(TAG, "Ignoring " + intent + " as it is not an " + ACTION_INSTALL + " intent");
            return START_NOT_STICKY;
        }

        if (!intent.hasExtra(EXTRA_APP) || !intent.hasExtra(EXTRA_APK)) {
            Utils.debugLog(TAG, urlString + " did not include both an App and Apk instance, ignoring");
            return START_NOT_STICKY;
        }

        if ((flags & START_FLAG_REDELIVERY) == START_FLAG_REDELIVERY
                && !DownloaderService.isQueuedOrActive(urlString)) {
            // TODO is there a case where we should allow an active urlString to pass through?
            Utils.debugLog(TAG, urlString + " finished downloading while InstallManagerService was killed.");
            cancelNotification(urlString);
            return START_NOT_STICKY;
        }

        App app = intent.getParcelableExtra(EXTRA_APP);
        Apk apk = intent.getParcelableExtra(EXTRA_APK);
        if (app == null || apk == null) {
            return START_NOT_STICKY;
        }
        addToActive(urlString, app, apk);

        NotificationCompat.Builder builder = createNotificationBuilder(urlString, apk);
        notificationManager.notify(urlString.hashCode(), builder.build());

        registerApkDownloaderReceivers(urlString, builder);
        getObb(urlString, apk.getMainObbUrl(), apk.getMainObbFile(), apk.obbMainFileSha256, builder);
        getObb(urlString, apk.getPatchObbUrl(), apk.getPatchObbFile(), apk.obbPatchFileSha256, builder);

        File apkFilePath = ApkCache.getApkDownloadPath(this, intent.getData());
        long apkFileSize = apkFilePath.length();
        if (!apkFilePath.exists() || apkFileSize < apk.size) {
            Utils.debugLog(TAG, "download " + urlString + " " + apkFilePath);
            DownloaderService.queue(this, urlString);
        } else if (ApkCache.apkIsCached(apkFilePath, apk)) {
            Utils.debugLog(TAG, "skip download, we have it, straight to install " + urlString + " " + apkFilePath);
            sendBroadcast(intent.getData(), Downloader.ACTION_STARTED, apkFilePath);
            sendBroadcast(intent.getData(), Downloader.ACTION_COMPLETE, apkFilePath);
        } else {
            Utils.debugLog(TAG, "delete and download again " + urlString + " " + apkFilePath);
            apkFilePath.delete();
            DownloaderService.queue(this, urlString);
        }
        return START_REDELIVER_INTENT; // if killed before completion, retry Intent
    }

    private void sendBroadcast(Uri uri, String action, File file) {
        Intent intent = new Intent(action);
        intent.setData(uri);
        intent.putExtra(Downloader.EXTRA_DOWNLOAD_PATH, file.getAbsolutePath());
        localBroadcastManager.sendBroadcast(intent);
    }

    /**
     * Check if any OBB files are available, and if so, download and install them. This
     * also deletes any obsolete OBB files, per the spec, since there can be only one
     * "main" and one "patch" OBB installed at a time.
     *
     * @see <a href="https://developer.android.com/google/play/expansion-files.html">APK Expansion Files</a>
     */
    private void getObb(final String urlString, String obbUrlString,
                        final File obbDestFile, final String sha256,
                        final NotificationCompat.Builder builder) {
        if (obbDestFile == null || obbDestFile.exists() || TextUtils.isEmpty(obbUrlString)) {
            return;
        }
        final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Downloader.ACTION_STARTED.equals(action)) {
                    Utils.debugLog(TAG, action + " " + intent);
                } else if (Downloader.ACTION_PROGRESS.equals(action)) {

                    int bytesRead = intent.getIntExtra(Downloader.EXTRA_BYTES_READ, 0);
                    int totalBytes = intent.getIntExtra(Downloader.EXTRA_TOTAL_BYTES, 0);
                    builder.setProgress(totalBytes, bytesRead, false);
                    notificationManager.notify(urlString.hashCode(), builder.build());
                } else if (Downloader.ACTION_COMPLETE.equals(action)) {
                    localBroadcastManager.unregisterReceiver(this);
                    File localFile = new File(intent.getStringExtra(Downloader.EXTRA_DOWNLOAD_PATH));
                    Uri localApkUri = Uri.fromFile(localFile);
                    Utils.debugLog(TAG, "OBB download completed " + intent.getDataString()
                            + " to " + localApkUri);

                    try {
                        if (Hasher.isFileMatchingHash(localFile, sha256, "SHA-256")) {
                            Utils.debugLog(TAG, "Installing OBB " + localFile + " to " + obbDestFile);
                            FileUtils.forceMkdirParent(obbDestFile);
                            FileUtils.copyFile(localFile, obbDestFile);
                            FileFilter filter = new WildcardFileFilter(
                                    obbDestFile.getName().substring(0, 4) + "*.obb");
                            for (File f : obbDestFile.getParentFile().listFiles(filter)) {
                                if (!f.equals(obbDestFile)) {
                                    Utils.debugLog(TAG, "Deleting obsolete OBB " + f);
                                    FileUtils.deleteQuietly(f);
                                }
                            }
                        } else {
                            Utils.debugLog(TAG, localFile + " deleted, did not match hash: " + sha256);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        FileUtils.deleteQuietly(localFile);
                    }
                } else if (Downloader.ACTION_INTERRUPTED.equals(action)) {
                    localBroadcastManager.unregisterReceiver(this);
                } else {
                    throw new RuntimeException("intent action not handled!");
                }
            }
        };
        DownloaderService.queue(this, obbUrlString);
        localBroadcastManager.registerReceiver(downloadReceiver,
                DownloaderService.getIntentFilter(obbUrlString));
    }

    private void registerApkDownloaderReceivers(String urlString, final NotificationCompat.Builder builder) {

        BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri downloadUri = intent.getData();
                String urlString = downloadUri.toString();

                switch (intent.getAction()) {
                    case Downloader.ACTION_STARTED:
                        // nothing to do
                        break;
                    case Downloader.ACTION_PROGRESS:
                        int bytesRead = intent.getIntExtra(Downloader.EXTRA_BYTES_READ, 0);
                        int totalBytes = intent.getIntExtra(Downloader.EXTRA_TOTAL_BYTES, 0);
                        builder.setProgress(totalBytes, bytesRead, false);
                        notificationManager.notify(urlString.hashCode(), builder.build());
                        break;
                    case Downloader.ACTION_COMPLETE:
                        File localFile = new File(intent.getStringExtra(Downloader.EXTRA_DOWNLOAD_PATH));
                        Uri localApkUri = Uri.fromFile(localFile);

                        Utils.debugLog(TAG, "download completed of " + urlString + " to " + localApkUri);

                        localBroadcastManager.unregisterReceiver(this);
                        registerInstallerReceivers(downloadUri);

                        Apk apk = ACTIVE_APKS.get(urlString);
                        PushAppStatusToServer.changeAppStatus(apk.packageName, String.valueOf(2));

                        InstallerService.install(context, localApkUri, downloadUri, apk);
                        break;
                    case Downloader.ACTION_INTERRUPTED:
                        removeFromActive(urlString);
                        localBroadcastManager.unregisterReceiver(this);
                        cancelNotification(urlString);
                        break;
                    default:
                        throw new RuntimeException("intent action not handled!");
                }
            }
        };

        localBroadcastManager.registerReceiver(downloadReceiver,
                DownloaderService.getIntentFilter(urlString));
    }

    private void registerInstallerReceivers(Uri downloadUri) {

        BroadcastReceiver installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String downloadUrl = intent.getDataString();
                Apk apk;
                switch (intent.getAction()) {
                    case Installer.ACTION_INSTALL_STARTED:
                        // nothing to do
                        break;
                    case Installer.ACTION_INSTALL_COMPLETE:
                        Apk apkComplete = removeFromActive(downloadUrl);

                        PackageManagerCompat.setInstaller(context, getPackageManager(), apkComplete.packageName);
                        if (PrivilegedInstaller.isDefault(context)) {
                            cancelNotification(downloadUrl);
                        }
                        if (Preferences.get().getPrefDeviceType().equalsIgnoreCase("dongle")) {
                            cancelNotification(downloadUrl);
                        }
                        Log.d(TAG, "onReceive: app installed" + apkComplete.packageName);
                        PushAppStatusToServer.changeAppStatus(apkComplete.packageName, String.valueOf(3));
                        localBroadcastManager.unregisterReceiver(this);
                        break;
                    case Installer.ACTION_INSTALL_INTERRUPTED:
                        apk = intent.getParcelableExtra(Installer.EXTRA_APK);
                        String errorMessage =
                                intent.getStringExtra(Installer.EXTRA_ERROR_MESSAGE);

                        // show notification if app details is not visible
                        if (!TextUtils.isEmpty(errorMessage)) {
                            App app = getAppFromActive(downloadUrl);
                            if (app == null) {
                                ContentResolver resolver = context.getContentResolver();
                                app = AppProvider.Helper.findSpecificApp(resolver, apk.packageName, apk.repo);
                            }
                            // show notification if app details is not visible
                            if (app != null && AppDetails.isAppVisible(app.packageName)) {
                                cancelNotification(downloadUrl);
                            } else {
                                notifyError(downloadUrl, app, errorMessage);
                            }
                        }
                        removeFromActive(downloadUrl);
                        localBroadcastManager.unregisterReceiver(this);
                        break;
                    case Installer.ACTION_INSTALL_USER_INTERACTION:
                        apk = intent.getParcelableExtra(Installer.EXTRA_APK);
                        PendingIntent installPendingIntent =
                                intent.getParcelableExtra(Installer.EXTRA_USER_INTERACTION_PI);

                        // show notification if app details is not visible
                        if (AppDetails.isAppVisible(apk.packageName)) {
                            cancelNotification(downloadUrl);
                        } else {
                            notifyDownloadComplete(apk, downloadUrl, installPendingIntent);
                        }

                        break;
                    default:
                        throw new RuntimeException("intent action not handled!");
                }
            }
        };

        localBroadcastManager.registerReceiver(installReceiver,
                Installer.getInstallIntentFilter(downloadUri));
    }

    private NotificationCompat.Builder createNotificationBuilder(String urlString, Apk apk) {
        int downloadUrlId = urlString.hashCode();
        return new NotificationCompat.Builder(this)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(getAppDetailsIntent(downloadUrlId, apk))
                .setContentTitle(getString(R.string.downloading_apk, getAppName(apk)))
                .addAction(R.drawable.ic_cancel_black_24dp, getString(R.string.cancel),
                        getCancelPendingIntent(urlString))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentText(IRIS_STORE)
                .setProgress(100, 0, true);
    }

    private String getAppName(Apk apk) {
        return ACTIVE_APPS.get(apk.packageName).name;
    }

    /**
     * Get a {@link PendingIntent} for a {@link Notification} to send when it
     * is clicked.  {@link AppDetails} handles {@code Intent}s that are missing
     * or bad {@link AppDetails#EXTRA_APPID}, so it does not need to be checked
     * here.
     */
    private PendingIntent getAppDetailsIntent(int requestCode, Apk apk) {
        Intent notifyIntent = new Intent(getApplicationContext(), AppDetails.class)
                .putExtra(AppDetails.EXTRA_APPID, apk.packageName);
        return TaskStackBuilder.create(getApplicationContext())
                .addParentStack(AppDetails.class)
                .addNextIntent(notifyIntent)
                .getPendingIntent(requestCode, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Post a notification about a completed download.  {@code packageName} must be a valid
     * and currently in the app index database.  This must create a new {@code Builder}
     * instance otherwise the progress/cancel stuff does not go away.
     *
     * @see <a href=https://code.google.com/p/android/issues/detail?id=47809> Issue 47809:
     * Removing the progress bar from a notification should cause the notification's content
     * text to return to normal size</a>
     */
    private void notifyDownloadComplete(Apk apk, String urlString, PendingIntent installPendingIntent) {
        String title;
        try {
            PackageManager pm = getPackageManager();
            title = String.format(getString(R.string.tap_to_update_format),
                    pm.getApplicationLabel(pm.getApplicationInfo(apk.packageName, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            String name = getAppName(apk);
            if (TextUtils.isEmpty(name) || name.equals(new App().name)) {
                ContentResolver resolver = getContentResolver();
                App app = AppProvider.Helper.findSpecificApp(resolver, apk.packageName, apk.repo,
                        new String[]{Schema.AppMetadataTable.Cols.NAME});
                if (app == null || TextUtils.isEmpty(app.name)) {
                    return;  // do not have a name to display, so leave notification as is
                }
                name = app.name;
            }
            title = String.format(getString(R.string.tap_to_install_format), name);
        }

        int downloadUrlId = urlString.hashCode();
        notificationManager.cancel(downloadUrlId);
        Notification notification = new NotificationCompat.Builder(this)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentTitle(title)
                .setContentIntent(installPendingIntent)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentText(getString(R.string.tap_to_install))
                .build();
        notificationManager.notify(downloadUrlId, notification);
    }

    private void notifyError(String urlString, App app, String text) {
        int downloadUrlId = urlString.hashCode();

        String name;
        if (app == null) {
            // if we have nothing else, show the APK filename
            String path = Uri.parse(urlString).getPath();
            name = path.substring(path.lastIndexOf('/'), path.length());
        } else {
            name = app.name;
        }
        String title = String.format(getString(R.string.install_error_notify_title), name);

        Intent errorDialogIntent = new Intent(this, ErrorDialogActivity.class);
        errorDialogIntent.putExtra(
                ErrorDialogActivity.EXTRA_TITLE, title);
        errorDialogIntent.putExtra(
                ErrorDialogActivity.EXTRA_MESSAGE, text);
        PendingIntent errorDialogPendingIntent = PendingIntent.getActivity(
                getApplicationContext(),
                downloadUrlId,
                errorDialogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this)
                        .setAutoCancel(true)
                        .setContentTitle(title)
                        .setContentIntent(errorDialogPendingIntent)
                        .setSmallIcon(R.drawable.ic_issues)
                        .setContentText(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(downloadUrlId, builder.build());
    }

    /**
     * Cancel the {@link Notification} tied to {@code urlString}, which is the
     * unique ID used to represent a given APK file. {@link String#hashCode()}
     * converts {@code urlString} to the required {@code int}.
     */
    private void cancelNotification(String urlString) {
        notificationManager.cancel(urlString.hashCode());
    }

    private static void addToActive(String urlString, App app, Apk apk) {
        ACTIVE_APKS.put(urlString, apk);
        ACTIVE_APPS.put(app.packageName, app);
    }

    /**
     * Always returns an {@link Apk} instance to avoid annoying null guards.
     */
    private static Apk getApkFromActive(String urlString) {
        Apk apk = ACTIVE_APKS.get(urlString);
        if (apk == null) {
            return new Apk();
        } else {
            return apk;
        }
    }

    /**
     * Remove the {@link App} and {@Apk} instances that are associated with
     * {@code urlString} from the {@link Map} of active apps.  This can be
     * called after this service has been destroyed and recreated based on the
     * {@link BroadcastReceiver}s, in which case {@code urlString} would not
     * find anything in the active maps.
     */
    private static App getAppFromActive(String urlString) {
        return ACTIVE_APPS.get(getApkFromActive(urlString).packageName);
    }

    /**
     * Remove the URL from this service, and return the {@link Apk}. This returns
     * an empty {@code Apk} instance if we get a null one so the code doesn't need
     * lots of null guards.
     */
    private static Apk removeFromActive(String urlString) {
        Apk apk = ACTIVE_APKS.remove(urlString);
        if (apk == null) {
            return new Apk();
        }
        ACTIVE_APPS.remove(apk.packageName);
        return apk;
    }

    private PendingIntent getCancelPendingIntent(String urlString) {
        Intent intent = new Intent(this, InstallManagerService.class)
                .setData(Uri.parse(urlString))
                .setAction(ACTION_CANCEL)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getService(this,
                urlString.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Install an APK, checking the cache and downloading if necessary before starting the process.
     * All notifications are sent as an {@link Intent} via local broadcasts to be received by
     *
     * @param context this app's {@link Context}
     */
    public static void queue(Context context, App app, Apk apk) {
        String urlString = apk.getUrl();
        Uri downloadUri = Uri.parse(urlString);
        Installer.sendBroadcastInstall(context, downloadUri, Installer.ACTION_INSTALL_STARTED, apk,
                null, null);
        Utils.debugLog(TAG, "queue " + app.packageName + " " + apk.versionCode + " from " + urlString);
        Intent intent = new Intent(context, InstallManagerService.class);
        intent.setAction(ACTION_INSTALL);
        intent.setData(downloadUri);
        intent.putExtra(EXTRA_APP, app);
        intent.putExtra(EXTRA_APK, apk);
        context.startService(intent);
    }

    public static void cancel(Context context, String urlString) {
        Intent intent = new Intent(context, InstallManagerService.class);
        intent.setAction(ACTION_CANCEL);
        intent.setData(Uri.parse(urlString));
        context.startService(intent);
    }

    /**
     * Returns a {@link Set} of the {@code urlString}s that are currently active.
     * {@code urlString}s are used as unique IDs throughout the
     * {@code InstallManagerService} process, either as a {@code String} or as an
     * {@code int} from {@link String#hashCode()}.
     */
    public static Set<String> getActiveDownloadUrls() {
        return ACTIVE_APKS.keySet();
    }

    /**
     * Returns a {@link Set} of the {@code packageName}s that are currently active.
     * {@code packageName}s are used as unique IDs for apps throughout all of
     * Android, F-Droid, and other apps stores.
     */
    public static Set<String> getActivePackageNames() {
        return ACTIVE_APPS.keySet();
    }
}
