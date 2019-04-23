package paszkiewicz.webfiledownloader;

import android.annotation.SuppressLint;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.ViewModel;
import android.content.Context;
import android.net.Uri;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.Loader;
import android.util.Log;

import java.io.File;

import static paszkiewicz.webfiledownloader.WebFileDownloader.ERROR_HTTP_RESPONSE;
import static paszkiewicz.webfiledownloader.WebFileDownloader.ERROR_WARNING_SIZE;

/**
 * Downloads or fetches file from cache, supporting partial downloads.
 */
public class WebFileViewModel extends ViewModel implements Loader.OnLoadCompleteListener<CacheableFile> {
    final static String TAG = "WebFileViewModel";
    private boolean isInitialized = false;
    private boolean isFinishing = false;
    private int cacheSize;
    private int timeout;

    private final LegacyCallback legacyCallback = new LegacyCallback();
    private MutableLiveData<Progress> progressMutableLiveData = new MutableLiveData<>();
    private LoaderTask loaderTask;
    private int currentLoaderId = 0;

    /**
     * Context we're using - always app context so it doesn't actually leak.
     */
    @SuppressLint("StaticFieldLeak")
    private Context appContext;


    public void initialize(int cacheSize,
                           int timeout) {
        if (!isInitialized) {
            this.cacheSize = cacheSize;
            this.timeout = timeout;
        }
        isInitialized = true;
    }

    /**
     * Get mutable live data for observing download progress and result.
     */
    public MutableLiveData<Progress> getDownload() {
        return progressMutableLiveData;
    }

    /**
     * Download new url. If file is already downloaded or downloading, returns false and does nothing.
     * This must be called on UI thread.
     *
     * @param fileSizeLimit max file size (in bytes) to download - will cancel download if it's larger. If < 1 there is no limit.
     * @return true if download started, false if it's already up
     */
    @MainThread
    public boolean downloadUrl(Context context, Uri url, int fileSizeLimit) {
        return downloadUrl(context, url, fileSizeLimit, false);
    }

    /**
     * Refresh current download.
     *
     * @return true if refresh is happening, false if there's no ongoing task
     */
    @MainThread
    public boolean refreshDownload() {
        Progress p = progressMutableLiveData.getValue();
        if (p == null) {
            return false; // download not up
        }
        if (loaderTask != null) {
            loaderTask.cancelFileDownload();
            loaderTask.cancelLoadInBackground();
            //abandon callbacks
            loaderTask.unregisterListener(this);
        }
        //now delete from cache
        try {
            ImageCacheManager cache = new ImageCacheManager(appContext, cacheSize);
            cache.invalidateCachedEntry(p.url.toString());
            cache.close();
        } catch (ImageCacheManager.CacheFailureException e) {
            // silent erorr?
            Log.e(TAG, "Error creating cache!");
            e.printStackTrace();
        }
        return downloadUrl(appContext, p.url, p.fileSizeLimit, true);
    }

    // internal download
    private boolean downloadUrl(Context context, Uri url, int fileSizeLimit, boolean forceCreate) {
        if (!isInitialized)
            throw new IllegalStateException("call initialize first!");
        Progress p = progressMutableLiveData.getValue();
        if (!forceCreate && p != null && p.isValid()) {
            return false; // download already up
        }
        //add new download task
        p = new Progress(url, fileSizeLimit);
        progressMutableLiveData.setValue(p);
        // prevent activity leaks by referencing app context
        appContext = context.getApplicationContext();
        if (url.getScheme().equals("content"))
            loaderTask = new ContentLoaderTask(appContext, url.toString(), fileSizeLimit, cacheSize, timeout);
        else
            loaderTask = new WebLoaderTask(appContext, url.toString(), fileSizeLimit, cacheSize, timeout);
        // use loader tasks in compatibility mode
        loaderTask.setCallback(legacyCallback);
        loaderTask.registerListener(++currentLoaderId, this);
        loaderTask.forceLoad();
        return true;
    }

    @Override
    protected void onCleared() {
        isFinishing = true;
        if (appContext != null) {
            //appcontext cleanup?
        }
        if (loaderTask != null) {
            loaderTask.cancelLoadInBackground();
            loaderTask.unregisterListener(this);
            loaderTask = null;
        }
    }


    // legacy callback from platform loader
    @Override
    public void onLoadComplete(@NonNull Loader<CacheableFile> loader, @Nullable CacheableFile data) {
        if (isFinishing || currentLoaderId != loader.getId())
            return;
        Progress p = progressMutableLiveData.getValue();
        if(p == null){
            Log.e(TAG, "onLoadComplete: missing download progress");
            return;
        }
        // parse loader result and throw it into progress object
        LoaderTask task = (LoaderTask) loader;
        if (task.getErrorCode() > 0) {
            if (task.getErrorCode() == ERROR_HTTP_RESPONSE) {
                p.error = new Error(task.getErrorMessage());
            } else if (task.getErrorCode() == ERROR_WARNING_SIZE) {
                p.isFileTooLarge = true;
                p.fileTooLargeMessage = task.getErrorMessage();
            } else {
                p.error = new Error(task.getErrorMessage(), task.getErrorCode());
            }
        } else {
            if (data == null)
                p.error = new Error(task.getErrorMessage(), task.getErrorCode());
            else {
                p.result = data.file;
            }
        }
        progressMutableLiveData.postValue(p);
    }

    // legacy callback from WebFileDownloader
    private class LegacyCallback implements WebFileDownloader.Callback{
        @Override
        public boolean isFinished() {
            return isFinishing;
        }

        @Override
        public void onDownloadError(int loaderId, String message, String stacktrace) {
            Progress p = progressMutableLiveData.getValue();
            if (p == null || currentLoaderId != loaderId) {
                Log.e(TAG, "onDownloadError: missing download progress or ID changed");
                return;
            }
            p.error = new Error(message, 0, stacktrace);
            progressMutableLiveData.postValue(p);
        }

        @Override
        public void onUpdateDownloadProgress(int loaderId, long current, long max, boolean isDeterminate) {
            Progress p = progressMutableLiveData.getValue();
            if (p == null || currentLoaderId != loaderId) {
                Log.e(TAG, "onUpdateDownloadProgress: missing download progress or ID changed");
                return;
            }
            p.progress = current;
            p.max = max;
            p.isDeterminate = isDeterminate;
            progressMutableLiveData.postValue(p);
        }

        @Override
        public void onFileLoaded(int loaderId, File downloadedFile) {
            Progress p = progressMutableLiveData.getValue();
            if (p == null || currentLoaderId != loaderId) {
                Log.e(TAG, "onFileLoaded: missing download progress or ID changed");
                return;
            }
            p.result = downloadedFile;
            progressMutableLiveData.postValue(p);
        }

        @Override
        public void onDownloadWarning(int loaderId, String message, long filesize) {
            // never triggered now; parsed in onLoadComplete
        }
    }


    /** Observed class with data about download. */
    public static class Progress {
        /**
         * Target url
         */
        public final Uri url;
        public final int fileSizeLimit;
        boolean isFileTooLarge = false;
        String fileTooLargeMessage;
        Error error;
        long progress;
        long max = -1;
        int status = 0;
        File result;
        boolean isDeterminate = false;

        private Progress(Uri url, int fileSizeLimit) {
            this.url = url;
            this.fileSizeLimit = fileSizeLimit;
        }

        /**
         * If this is non null download failed.
         */
        @Nullable
        public Error getError() {
            return error;
        }

        /** Current progress. */
        public long getProgress() {
            return progress;
        }

        /** Max progress, might be -1 if not determined yet. */
        public long getMax() {
            return max;
        }

        /**
         * Download status stub; always 0.
         */
        public int getStatus() {
            return status;
        }

        /**
         * If this is true download was cancelled.
         */
        public boolean isFileTooLarge() {
            return isFileTooLarge;
        }

        public String getFileTooLargeMessage() {
            return fileTooLargeMessage;
        }

        public boolean isDeterminate() {
            return isDeterminate;
        }

        /**
         * If true download task is still alive.
         */
        public boolean isRunning() {
            return result == null && !isFileTooLarge && error == null;
        }

        /**
         * If true this task haven't failed or wasn't cancelled.
         */
        public boolean isValid() {
            return result != null || (!isFileTooLarge && error == null);
        }

        /**
         * Result - if set download concluded.
         */
        public File getResult() {
            return result;
        }
    }

    /**
     * Possible download error with readable strings.
     */
    public static class Error {
        final static int ERROR_CREATING_CACHE = R.string.webfiledownloader_error_cacheFailure;
        final static int ERROR_HTTP_RESPONSE = R.string.webfiledownloader_error_httpResponse;
        final static int ERROR_HTTP_NOHOST = R.string.webfiledownloader_error_httpNoHost;
        final static int ERROR_TIMEOUT = R.string.webfiledownloader_error_httpTimeout;
        final static int ERROR_SOCKET = R.string.webfiledownloader_error_socket;
        final static int ERROR_UNVERIFIED = R.string.webfiledownloader_error_unverified;
        final static int ERROR_OTHER = R.string.webfiledownloader_error_other;

        public final String message;
        public final int code;
        public final String debug_exceptionStackTrace;

        public Error(String message) {
            this.message = message;
            code = 0;
            debug_exceptionStackTrace = null;
        }

        public Error(String message, int code) {
            this.message = message;
            this.code = code;
            debug_exceptionStackTrace = null;
        }

        public Error(String message, int code, String debug_exceptionStackTrace) {
            this.message = message;
            this.code = code;
            this.debug_exceptionStackTrace = debug_exceptionStackTrace;
        }
    }
}
