package paszkiewicz.webfiledownloader;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;

import java.io.File;

/**
 * Creates a loader that downloads a file or fetches from a cache. <br> Supports partial
 * downloads.<br> Uses androids AsyncLoader and LoaderManager to manage activity life cycle.
 * todo: delete this implementation
 * @deprecated use viewmodel implementation: {@link WebFileViewModel}
 */
public class WebFileDownloader implements LoaderManager.LoaderCallbacks<CacheableFile> {
	final static int ERROR_CREATING_CACHE = R.string.webfiledownloader_error_cacheFailure;
	final static int ERROR_HTTP_RESPONSE = R.string.webfiledownloader_error_httpResponse;
	final static int ERROR_HTTP_NOHOST = R.string.webfiledownloader_error_httpNoHost;
	final static int ERROR_TIMEOUT = R.string.webfiledownloader_error_httpTimeout;
	final static int ERROR_SOCKET = R.string.webfiledownloader_error_socket;
	final static int ERROR_UNVERIFIED = R.string.webfiledownloader_error_unverified;
	final static int ERROR_OTHER = R.string.webfiledownloader_error_other;

	/**
	 * Use this as onDownloadError code to call {@link Callback#onDownloadWarning(int, String,
	 * long)}
	 */
	final static int ERROR_WARNING_SIZE = R.string.webfiledownloader_warning_too_large;

	private final String url;
	private final int sizeWarning;
	private final int cacheSize;
	private final int timeout;
	private final boolean isContent;
	private final int loaderId;

	private boolean acceptedMobileWarning;

	private FragmentActivity activity;
	private Callback callback;
	private LoaderTask task;


	/**
	 * Create new loader or reconnect to existing one
	 *
	 * @param activity  activity, must implement {@link Callback}
	 * @param url       url of file to download
	 * @param cacheSize size of cache (amount of images to hold)
	 * @param timeout   time (in milliseconds) to kill connection
	 * @param loaderId
	 */
	public WebFileDownloader(FragmentActivity activity, Uri url, int sizeWarning, int cacheSize,
							 int timeout, int loaderId) {
		this.activity = activity;
		try {
			callback = (Callback) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException("WebFileDownloader - " + activity.getClass().getName() +
					"does not implement WebFileDownloader.Callback!");
		}
		this.url = url.toString();
		this.sizeWarning = sizeWarning;
		this.cacheSize = cacheSize;
		this.timeout = timeout;
		this.loaderId = loaderId;

		isContent = url.getScheme().equals("content");
		task = (LoaderTask) activity.getSupportLoaderManager().initLoader(loaderId, null, this);
		task.setCallback((WebFileDownloader.Callback)activity);
	}

	/**
	 * Cancel load task without abandoning the progress
	 */
	public void cancel() {
		task.cancelLoadInBackground();
		activity.getLoaderManager().destroyLoader(loaderId);
	}

	/**
	 * Destroy any partial progress
	 */
	public void destroy() {
		task.cancelFileDownload();
		task.cancelLoadInBackground();

		//now delete that from cache
		try {
			ImageCacheManager cache = new ImageCacheManager(activity, cacheSize);
			cache.invalidateCachedEntry(url);
			cache.close();
		} catch (ImageCacheManager.CacheFailureException e) {
			error(ERROR_CREATING_CACHE);
			return;
		}

		activity.getLoaderManager().destroyLoader(loaderId);
	}

	/**
	 * Restarts the loader that won't stop even if filesize limit is exceeded
	 */
	public void unpause() {
		activity.getSupportLoaderManager().destroyLoader(loaderId);
		acceptedMobileWarning = true;
		task = (LoaderTask) activity.getSupportLoaderManager().initLoader(loaderId, null, this);
		task.setCallback((WebFileDownloader.Callback)activity);
	}

	@Override
	public Loader<CacheableFile> onCreateLoader(int id, Bundle args) {
		int mobileWarning = acceptedMobileWarning ? -1 : sizeWarning;
		if (isContent)
			return new ContentLoaderTask(activity, url, mobileWarning, cacheSize, timeout);
		return new WebLoaderTask(activity, url, mobileWarning, cacheSize, timeout);
	}

	@Override
	public void onLoadFinished(Loader<CacheableFile> loader, CacheableFile data) {
		if (callback.isFinished())
			return;

		LoaderTask task = (LoaderTask) loader;
		if (task.getErrorCode() > 0) {
			if (task.getErrorCode() == ERROR_HTTP_RESPONSE) {
				error(task.getErrorMessage());
			} else if (task.getErrorCode() == ERROR_WARNING_SIZE) {
				callback.onDownloadWarning(loaderId, task.getErrorMessage(), task.getFileLength());
			} else error(task.getErrorCode(), task.getErrorMessage());
		} else {
			if (data == null)
				error(task.getErrorCode(), task.getErrorMessage());
			else {
				callback.onFileLoaded(loaderId, data.file);
			}
		}
	}

	@Override
	public void onLoaderReset(Loader<CacheableFile> loader) {

	}

	/**
	 * Display onDownloadError while loading image
	 */
	public void error(int messageResource, String stacktrace) {
		error(activity.getString(messageResource), stacktrace);
	}

	/**
	 * Display onDownloadError while loading image
	 */
	public void error(int messageResource) {
		error(activity.getString(messageResource));
	}

	/**
	 * Display onDownloadError while loading image
	 */
	public void error(String message) {
		error(message, null);
	}

	/**
	 * Display onDownloadError while loading image
	 */
	public void error(final String message, final String exceptionStackTrace) {
		callback.onDownloadError(loaderId, message, exceptionStackTrace);
	}

	public interface Callback {
		/**
		 * Check if activity is alive
		 *
		 * @return True to prevent other callback calls, false if it's still alive and operating
		 * normally
		 */
		boolean isFinished();

		/**
		 * Download failed
		 *
		 * @param loaderId   id of loader
		 * @param message    message to display
		 * @param stacktrace stacktrace to show in popup (debug use only)
		 */
		void onDownloadError(int loaderId, String message, String stacktrace);

		/**
		 * Update progress
		 *
		 * @param loaderId      id of loader
		 * @param current       progress
		 * @param max           progress
		 * @param isDeterminate False if still connecting, true if connection gets established and
		 */
		void onUpdateDownloadProgress(int loaderId, long current, long max, boolean isDeterminate);

		/**
		 * Core callback, ran after download finishes or cache hit was successful
		 *
		 * @param loaderId       if of loader
		 * @param downloadedFile complete file in Cache
		 */
		void onFileLoaded(int loaderId, File downloadedFile);

		/**
		 * Show warning and wait for {@link #unpause()} call
		 *
		 * @param loaderId id of loader
		 * @param message  formatted download that caused the pause (eg. 1.2MB)
		 * @param filesize filesize in bytes
		 */
		void onDownloadWarning(int loaderId, String message, long filesize);
	}
}
