package paszkiewicz.webfiledownloader;

import android.content.Context;
import android.text.format.Formatter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Task that downloads file from the web.
 */
class WebLoaderTask extends LoaderTask {
	private final static String ETAG = "ETag";
	private final static String LAST_MODIFIED = "Last-Modified";

	private HttpURLConnection connection = null;

	WebLoaderTask(Context context, String url, int mobileWarning, int cacheSize, int timeout) {
		super(context, url, mobileWarning, cacheSize, timeout);
	}

	@Override
	protected InputStream openInputStream(CacheableFile imageFile) throws Exception {
		connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setConnectTimeout(timeout);
		if (imageFile.isPartiallyLoaded()) {
			//if image is not loaded fully try to continue
			connection.setRequestProperty("Range", "bytes=" + imageFile.file.length()
					+ "-");
			if (imageFile.length > 0)
				updateProgress((int) imageFile.file.length(), imageFile.length, false);
		}

		connection.connect();
		if (isStopped()) {
			return null;
		}


		int httpResponseCode = connection.getResponseCode();
		setErrorMessage(httpResponseCode + " - " + connection.getResponseMessage());

		if (httpResponseCode == HttpURLConnection.HTTP_PARTIAL) {
			//server accepted our partial load request, check if ETag is same
			if (imageFile.eTag != null && imageFile.eTag.equals(getResponseEtag())) {
				fileLength = imageFile.length;
				imageFile.partIsValid = true;
			} else {
				//if ETag is invalid we have to request http again and get full file
				connection.disconnect();
				connection = (HttpURLConnection) new URL(url).openConnection();
				connection.setConnectTimeout(timeout);
				connection.connect();
			}
		} else if (httpResponseCode != HttpURLConnection.HTTP_OK) {
			//something failed
			setErrorCode(WebFileDownloader.ERROR_HTTP_RESPONSE);
			return null;
		}

		if (!imageFile.partIsValid) {
			//we download from scratch
			fileLength = connection.getContentLength();
			imageFile.eTag = getResponseEtag();
			imageFile.length = fileLength;
		}

		if (mobileWarning >= 0) {
			long downloadSize = (fileLength - imageFile.file.length());
			if (downloadSize > mobileWarning) {
				setErrorCode(WebFileDownloader.ERROR_WARNING_SIZE);
				setErrorMessage(Formatter.formatShortFileSize(getContext(), downloadSize));
				return null;
			}
		}
		return connection.getInputStream();
	}

	@Override
	protected void doFinally() {
		if (connection != null)
			connection.disconnect();
	}

	/**
	 * Get ETAG header provided by server, if its missing use last-modified instead
	 *
	 * @return etag or last-modified value
	 */
	private String getResponseEtag() {
		String ret = connection.getHeaderField(ETAG);
		if (ret == null || ret.isEmpty())
			ret = connection.getHeaderField(LAST_MODIFIED);
		return ret;
	}
}
