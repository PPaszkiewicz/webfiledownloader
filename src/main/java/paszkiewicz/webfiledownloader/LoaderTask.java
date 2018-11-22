package paszkiewicz.webfiledownloader;

import android.app.Activity;
import android.content.Context;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.AsyncTaskLoader;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Task performing background loading of a file
 */
abstract class LoaderTask extends AsyncTaskLoader<CacheableFile> {
	protected final String url;
	protected final int mobileWarning;
	protected final int cacheSize;
	protected final int timeout;
	protected FragmentActivity  activity;
	protected WebFileDownloader.Callback callback;

	protected long fileLength;
	private int errorCode = 0;
	private boolean isFileDownloadCancelled = false;
	private String errorMessage = null;

	public LoaderTask(Context context, String url, int mobileWarning, int cacheSize, int timeout) {
		super(context);

		FragmentActivity  activity = (FragmentActivity ) context;
		setActivity(activity);

		this.url = url;
		this.mobileWarning = mobileWarning;
		this.cacheSize = cacheSize;
		this.timeout = timeout;
		onContentChanged();
	}

	public LoaderTask setActivity(FragmentActivity activity) {
		this.activity = activity;
		this.callback = (WebFileDownloader.Callback) activity;
		return this;
	}

	/**
	 * Call to abandon current loading - don't save partial progress
	 */
	public void cancelFileDownload() {
		isFileDownloadCancelled = true;
	}

	/**
	 * @return onDownloadError code when loading failed
	 */
	public int getErrorCode() {
		return errorCode;
	}

	/**
	 * Error code must be set to return a valid onDownloadError
	 *
	 * @param errorCode onDownloadError code
	 */
	public void setErrorCode(int errorCode) {
		this.errorCode = errorCode;
	}

	/**
	 * @return message to display after onDownloadError
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * @param errorMessage message to display on onDownloadError
	 */
	protected void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	/**
	 * @return size of remote file
	 */
	public long getFileLength() {
		return fileLength;
	}

	@Override
	protected void onStartLoading() {
		if (takeContentChanged())
			forceLoad();
	}

	@Override
	protected void onStopLoading() {
		cancelLoad();
	}

	@Override
	public CacheableFile loadInBackground() {
		//variables that get cleaned up in final block
		CacheableFile imageFile = null;
		InputStream input = null;
		OutputStream output = null;
		ImageCacheManager cache = null;

		try {
			cache = new ImageCacheManager(activity, cacheSize);
			//get cached image or image to save stream to, if it's loaded return it instead
			imageFile = cache.getCachedUrlFile(url);
			if (imageFile.isLoaded()) {
				return imageFile;
			}

			// start downloading the file
			input = openInputStream(imageFile);
			if (input == null)
				return null;

			output = new FileOutputStream(imageFile.file, imageFile.partIsValid);

			long downloadProgress = 0;
			//restore previous download progress
			if (imageFile.partIsValid)
				downloadProgress = imageFile.file.length();

			byte data[] = new byte[4096];
			int count;
			//loop read input stream
			while ((count = input.read(data)) != -1) {
				if (isStopped()) {
					return null;
				}

				downloadProgress += count;
				// publishing the progress....
				if (fileLength > 0) // only if total length is known
					updateProgress((int) downloadProgress, fileLength, true);
				output.write(data, 0, count);
			}

		} catch (ImageCacheManager.CacheFailureException cacheFail) {
			errorCode = WebFileDownloader.ERROR_CREATING_CACHE;
			return null;
		} catch (UnknownHostException noHost) {
			errorCode = WebFileDownloader.ERROR_HTTP_NOHOST;
			return null;
		} catch (SocketTimeoutException timeOutException) {
			errorCode = WebFileDownloader.ERROR_TIMEOUT;
			return null;
		} catch (SocketException socketException) {
			errorCode = WebFileDownloader.ERROR_SOCKET;
			return null;
		} catch (IOException ioException) {
			errorCode = WebFileDownloader.ERROR_UNVERIFIED;
			return null;
		} catch (Exception e) {
			e.printStackTrace();
			errorCode = WebFileDownloader.ERROR_OTHER;
			errorMessage = Util.createMessageFromException(e);
			return null;
		} finally {
			try {
				if (output != null)
					output.close();
				if (input != null)
					input.close();
				if (cache != null) {
					if (!isFileDownloadCancelled && imageFile != null && imageFile
							.isPartiallyLoaded())
						cache.savePartialProgress(imageFile);
					cache.close();
				}
				if (isFileDownloadCancelled && imageFile != null) {
					//delete both here and from cache manager
					//since otherwise we miss some when mashing refresh button
					//noinspection ResultOfMethodCallIgnored
					imageFile.file.delete();
				}
			} catch (Exception ignored) {
			}
			doFinally();
		}
		return imageFile;
	}

	/**
	 * Update progress bar in containing activity
	 *
	 * @param current       progress
	 * @param max           progress
	 * @param isDeterminate set to false if we're not progressing yet, eg. we restored partial
	 *                      download
	 */
	protected void updateProgress(final long current, final long max, final boolean
			isDeterminate) {
		activity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (!isFileDownloadCancelled)
					callback.onUpdateDownloadProgress(getId(), current, max, isDeterminate);
			}
		});
	}

	/**
	 * Check if task should stop
	 *
	 * @return true if this task is no longer needed
	 */
	protected boolean isStopped() {
		return callback.isFinished() || isFileDownloadCancelled || isLoadInBackgroundCanceled();
	}

	/**
	 * Open input stream for the loader<br> Set {@link #fileLength} to show the loading bar<br>
	 *
	 * @param imageFile cached image, set {@link CacheableFile#partIsValid} = true to continue old
	 *                  download
	 * @return opened cached input stream. set {@link #errorCode} and return null to call
	 * onDownloadError
	 * @throws Exception catch exceptions in main loop try block
	 */
	abstract protected InputStream openInputStream(CacheableFile imageFile) throws Exception;

	/**
	 * Override this to perform cleanup in thread's finally block
	 */
	protected void doFinally() {
	}

}
