package paszkiewicz.webfiledownloader;

import android.content.Context;

import java.io.File;

/**
 * Static methods
 */

abstract class Util {
	/**
	 * Convert an exception into multiline string
	 *
	 * @param e exception to parse
	 * @return String with exception
	 */
	public static String createMessageFromException(Exception e) {
		StackTraceElement[] stack = e.getStackTrace();
		StringBuilder ret = new StringBuilder();
		ret.append(e.toString()).append("\n\t");
		for (StackTraceElement s : stack) {
			ret.append(s).append("\n\t");
		}
		return ret.toString();
	}

	/**
	 * Gets or forces a creation of cache directory for this app<br> Tries to create external cache
	 * dir before using internal one
	 *
	 * @param context this apps context
	 * @return cache dir, or null if failed
	 */
	public static File getOrCreateCacheDir(Context context) {
		if (context == null)
			return null;

		File f = context.getExternalCacheDir();
		if (validateDir(f))
			return f;

		f = context.getCacheDir();
		if (validateDir(f))
			return f;
		return null;
	}

	/**
	 * @param file directory to create
	 * @return true if exists or managed to recreate, false it not
	 */
	private static boolean validateDir(File file) {
		if (file != null) {
			return file.exists() || file.mkdirs();
		}
		return false;
	}

	/**
	 * Rip extension from string
	 *
	 * @param path string with extension
	 * @return lowercased extension (without dot) or null if string has no dot to start reading
	 * from
	 */
	public static String getExtension(String path) {
		if (path.lastIndexOf('.') == -1)
			return null;
		String ext = path.substring(path.lastIndexOf('.') + 1);
		ext = ext.replace("jpg", "jpeg").toLowerCase();
		return ext;
	}
}
