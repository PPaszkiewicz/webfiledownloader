package paszkiewicz.webfiledownloader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.InputStream;

/**
 * Task that opens input stream from remote content uri (no column indicating local file uri)
 */
class ContentLoaderTask extends LoaderTask {
	ContentLoaderTask(Context context, String url, int mobileWarning, int cacheSize, int timeout) {
		super(context, url, mobileWarning, cacheSize, timeout);
	}

	@Override
	protected InputStream openInputStream(CacheableFile imageFile) throws Exception {
		Uri uri = Uri.parse(url);
		Cursor c = activity.getContentResolver().query(uri, null, null, null, null);
		if (c != null && c.moveToFirst()) {
			long fileLen = c.getLong(c.getColumnIndex(OpenableColumns.SIZE));
			fileLength = fileLen > 0 ? fileLen : 0;
			c.close();
		}
		return activity.getContentResolver().openInputStream(uri);
	}
}
