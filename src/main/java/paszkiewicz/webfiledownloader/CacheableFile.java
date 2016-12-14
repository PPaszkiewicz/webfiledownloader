package paszkiewicz.webfiledownloader;

import android.database.Cursor;

import java.io.File;

/**
 * Holds cached image and data read from db, or about to be injected to db
 */
class CacheableFile {
	final String url;
	final File file;
	long length = -1;
	String eTag;
	/**
	 * Not fetched from database, set to true after validating ETag with server
	 */
	boolean partIsValid = false;

	/**
	 * Used on cache hit, we can load all details here<br> Refer to {@link
	 * ImageCacheManager.CacheTable#PROJECTION} for cursor data order
	 *
	 * @param c cursor with row of the file
	 * @param f file loaded from cache
	 */
	CacheableFile(Cursor c, File f) {
		url = c.getString(0);
		file = f;
		length = c.getLong(3);
		eTag = c.getString(4);
	}

	/**
	 * Used on cache miss, we only know url and empty file in cache
	 *
	 * @param url url of file
	 * @param f   empty file
	 */
	CacheableFile(String url, File f) {
		this.url = url;
		this.file = f;
	}

	/**
	 * Check if file is loaded, if it is it can be swiftly returned
	 *
	 * @return true if file is complete, false otherwise
	 */
	boolean isLoaded() {
		long fileSize = file.length();
		return fileSize > 0 && fileSize >= length;
	}

	/**
	 * Check if file is partially loaded so we can continue downloading it
	 *
	 * @return true if file is partially loaded, false if its empty or complete
	 */
	boolean isPartiallyLoaded() {
		return file.length() > 0 && file.length() < length;
	}
}
