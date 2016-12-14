package paszkiewicz.webfiledownloader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

import java.io.File;
import java.io.IOException;

/**
 * Caches images in the database.<br> Keeps reference to the latest accessed images, drops entries
 * for oldest images or missing files automatically.
 */
class ImageCacheManager extends SQLiteOpenHelper {
	private final static int DB_VERSION = 2;
	private final static String DB_NAME = "ImageCache.db";
	private final static String COMMAND_CREATE =
			"CREATE TABLE " + CacheTable.TABLE_NAME + " ( " +
					CacheTable.COLUMN_NAME_URL + " text primary key not null, " +
					CacheTable.COLUMN_NAME_FILENAME + " text not null, " +
					CacheTable.COLUMN_NAME_DATE + " integer not null, " +
					CacheTable.COLUMN_NAME_SIZE + " integer, " +
					CacheTable.COLUMN_NAME_ETAG + " text" + ")";

	private final static String COMMAND_DELETE =
			"DROP TABLE IF EXISTS " + CacheTable.TABLE_NAME;

	private final int maxCacheSize;
	private final File cacheDir;


	/**
	 * Create cache manager with fixed cache size
	 *
	 * @param context      app context
	 * @param maxCacheSize max amount of cached files
	 * @throws CacheFailureException
	 */
	ImageCacheManager(Context context, int maxCacheSize) throws CacheFailureException {
		super(context, makeDBPath(context), null, DB_VERSION);
		this.cacheDir = Util.getOrCreateCacheDir(context);
		this.maxCacheSize = maxCacheSize;
	}

	private static String makeDBPath(Context context) throws CacheFailureException {
		File cacheFile = Util.getOrCreateCacheDir(context);
		if (cacheFile == null)
			throw new CacheFailureException();
		return new File(cacheFile.getAbsolutePath(), DB_NAME).getAbsolutePath();
	}

	/**
	 * Get cached file for specified url
	 *
	 * @param url url to look up
	 * @return Cached file or null if missing
	 */
	synchronized CacheableFile getCachedUrlFile(String url) {
		CacheableFile cachedFile;
		SQLiteDatabase db = getWritableDatabase();
		Cursor c = db.query(
				CacheTable.TABLE_NAME,
				CacheTable.PROJECTION,
				whereUrl(url),
				null, null, null, null);

		if (c.moveToFirst()) {
			File f = new File(cacheDir, c.getString(1));
			updateURLDate(url, db);
			cachedFile = new CacheableFile(c, f);
		} else {
			cachedFile = insertUrlToCache(url, db);
		}
		c.close();
		db.close();
		return cachedFile;
	}

	/**
	 * Insert data of partially loaded file into database
	 *
	 * @param file CacheableFile with length and etag set
	 */
	synchronized void savePartialProgress(CacheableFile file) {
		SQLiteDatabase db = getWritableDatabase();
		ContentValues val = new ContentValues();
		val.put(CacheTable.COLUMN_NAME_SIZE, file.length);
		val.put(CacheTable.COLUMN_NAME_ETAG, file.eTag);
		db.update(CacheTable.TABLE_NAME,
				val,
				whereUrl(file.url),
				null);
		db.close();
	}

	/**
	 * Save new url in db
	 *
	 * @param url new url inserted into database
	 * @return filename to create that will be referenced by this entry
	 */
	private CacheableFile insertUrlToCache(String url, SQLiteDatabase db) {
		CacheableFile retFile = null;
		String time = String.valueOf(System.currentTimeMillis());
		String filename = time + "." + Util.getExtension(url);
		ContentValues val = new ContentValues();
		val.put(CacheTable.COLUMN_NAME_URL, url);
		val.put(CacheTable.COLUMN_NAME_FILENAME, filename);
		val.put(CacheTable.COLUMN_NAME_DATE, time);

		if (db.insert(CacheTable.TABLE_NAME, null, val) > 0) {
			File f = new File(cacheDir, filename);
			retFile = new CacheableFile(url, f);
		}
		flushOldEntries(db);
		return retFile;
	}

	/**
	 * Update date for entry that already existed
	 *
	 * @param url updated rows url
	 * @param db  readable database
	 */
	private void updateURLDate(String url, SQLiteDatabase db) {
		ContentValues val = new ContentValues();
		val.put(CacheTable.COLUMN_NAME_DATE, System.currentTimeMillis());
		db.update(CacheTable.TABLE_NAME,
				val,
				whereUrl(url),
				null);
	}

	/**
	 * Flush all old entries from db and disk
	 *
	 * @param db writeable database
	 */
	private void flushOldEntries(SQLiteDatabase db) {
		Cursor c = db.query(
				CacheTable.TABLE_NAME,
				CacheTable.SHORT_PROJECTION,
				null, null, null, null,
				CacheTable.COLUMN_NAME_DATE + " DESC",
				maxCacheSize + ",100");

		while (c.moveToNext()) {
			deleteEntry(c.getString(0), db);
			deleteFile(c.getString(1));
		}
		c.close();
	}

	/**
	 * Delete cached URLs file and from database
	 *
	 * @param url url of cached file
	 */
	synchronized void invalidateCachedEntry(String url) {
		SQLiteDatabase db = getWritableDatabase();
		Cursor c = db.query(
				CacheTable.TABLE_NAME,
				CacheTable.SHORT_PROJECTION,
				whereUrl(url),
				null, null, null, null);
		if (c.moveToNext()) {
			deleteFile(c.getString(1));
		}
		invalidateRow(url, db);
		c.close();
		db.close();
	}

	/**
	 * Invalidate cached Row
	 *
	 * @param url updated rows url
	 * @param db  readable database
	 */
	private void invalidateRow(String url, SQLiteDatabase db) {
		ContentValues val = new ContentValues();
		String time = String.valueOf(System.currentTimeMillis());
		String filename = time + "." + Util.getExtension(url);
		val.put(CacheTable.COLUMN_NAME_FILENAME, filename);
		val.put(CacheTable.COLUMN_NAME_DATE, time);
		val.putNull(CacheTable.COLUMN_NAME_SIZE);
		val.putNull(CacheTable.COLUMN_NAME_ETAG);
		db.update(CacheTable.TABLE_NAME,
				val,
				whereUrl(url),
				null);
	}

	/**
	 * Delete an entry from database
	 *
	 * @param urlEntry url for row
	 * @param db       readable database
	 */
	private void deleteEntry(String urlEntry, SQLiteDatabase db) {
		db.delete(CacheTable.TABLE_NAME,
				whereUrl(urlEntry), null);
	}

	/**
	 * Delete file
	 *
	 * @param filename name of file residing in apps cache
	 */
	private void deleteFile(String filename) {
		File f = new File(cacheDir, filename);
		if (f.exists()) {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
		}
	}

	/**
	 * String for WHERE clause with escaped url
	 *
	 * @param url url filter, Do NOT use url fetched from db since it already is escaped and will
	 *            fail
	 * @return valid where clause
	 */
	private String whereUrl(String url) {
		return CacheTable.COLUMN_NAME_URL + " = \"" + url + "\"";
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(COMMAND_CREATE);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		db.execSQL(COMMAND_DELETE);
		onCreate(db);
	}

	@Override
	public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		onUpgrade(db, oldVersion, newVersion);
	}

	static abstract class CacheTable implements BaseColumns {
		private static final String TABLE_NAME = "CachedImages";
		private static final String COLUMN_NAME_URL = "url";
		private static final String COLUMN_NAME_FILENAME = "filename";
		private static final String COLUMN_NAME_DATE = "date";

		private static final String COLUMN_NAME_SIZE = "filesize";
		private static final String COLUMN_NAME_ETAG = "Etag";

		private final static String[] PROJECTION = {
				COLUMN_NAME_URL,
				COLUMN_NAME_FILENAME,
				COLUMN_NAME_DATE,
				COLUMN_NAME_SIZE,
				COLUMN_NAME_ETAG};

		private final static String[] SHORT_PROJECTION = {
				COLUMN_NAME_URL,
				COLUMN_NAME_FILENAME
		};
	}

	/**
	 * Exception to throw when we fail to put file in apps cache
	 */
	public static class CacheFailureException extends IOException {

	}
}
