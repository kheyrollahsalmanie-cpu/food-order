package ir.food.order;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int FILE_REQ = 1001;
    private static final int ARCHIVE_TREE_REQ = 1002;
    private static final String PREFS = "food_order_archive_prefs";
    private static final String TREE_URI_KEY = "archive_tree_uri";
    private static final String ARCHIVE_DIR = "Food_Label_Archive";
    private static final Pattern ARCHIVE_FILE = Pattern.compile("^لیبل-\\d{4}-\\d{2}-\\d{2}\\.json$");

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new ArchiveBridge(), "AndroidArchive");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private Uri savedTreeUri() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TREE_URI_KEY, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Uri.parse(raw); } catch (Exception e) { return null; }
    }

    private Uri rootDocumentUri(Uri treeUri) {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
    }

    private Uri findChild(Uri treeUri, Uri parentDocumentUri, String displayName) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parentDocumentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return null;
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                if (displayName.equals(cursor.getString(nameCol))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol));
                }
            }
        }
        return null;
    }

    private Uri archiveDirectory(boolean create) throws Exception {
        Uri tree = savedTreeUri();
        if (tree == null) return null;
        Uri root = rootDocumentUri(tree);
        Uri dir = findChild(tree, root, ARCHIVE_DIR);
        if (dir == null && create) {
            dir = DocumentsContract.createDocument(getContentResolver(), root,
                    DocumentsContract.Document.MIME_TYPE_DIR, ARCHIVE_DIR);
        }
        return dir;
    }

    private String readDocument(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("امکان خواندن فایل بایگانی وجود ندارد.");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void writeDocument(Uri uri, String content) throws Exception {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("امکان نوشتن در پوشه انتخاب‌شده وجود ندارد.");
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void deleteIfExists(Uri uri) {
        if (uri == null) return;
        try { DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
    }

    private String writeArchive(String fileName, String content) throws Exception {
        if (fileName == null || !ARCHIVE_FILE.matcher(fileName).matches())
            throw new IllegalArgumentException("نام فایل بایگانی معتبر نیست.");
        Uri tree = savedTreeUri();
        if (tree == null) throw new IllegalStateException("ابتدا پوشه بایگانی را انتخاب کنید.");
        Uri dir = archiveDirectory(true);
        if (dir == null) throw new IllegalStateException("ساخت پوشه بایگانی ممکن نشد.");

        String tempName = fileName + ".tmp";
        String backupName = fileName + ".bak";
        deleteIfExists(findChild(tree, dir, tempName));
        deleteIfExists(findChild(tree, dir, backupName));

        Uri temp = DocumentsContract.createDocument(getContentResolver(), dir, "application/json", tempName);
        if (temp == null) throw new IllegalStateException("ساخت فایل موقت بایگانی ناموفق بود.");
        writeDocument(temp, content);
        if (!content.equals(readDocument(temp))) {
            deleteIfExists(temp);
            throw new IllegalStateException("بررسی فایل موقت بایگانی ناموفق بود.");
        }

        Uri existing = findChild(tree, dir, fileName);
        Uri backup = null;
        if (existing != null) {
            backup = DocumentsContract.renameDocument(getContentResolver(), existing, backupName);
            if (backup == null) {
                deleteIfExists(temp);
                throw new IllegalStateException("نسخه قبلی فایل قابل پشتیبان‌گیری نبود؛ فایل قبلی حفظ شد.");
            }
        }

        Uri finalUri = DocumentsContract.renameDocument(getContentResolver(), temp, fileName);
        if (finalUri == null) {
            if (backup != null) {
                try { DocumentsContract.renameDocument(getContentResolver(), backup, fileName); } catch (Exception ignored) { }
            }
            deleteIfExists(temp);
            throw new IllegalStateException("ثبت نهایی فایل بایگانی ناموفق بود.");
        }

        if (!content.equals(readDocument(finalUri))) {
            deleteIfExists(finalUri);
            if (backup != null) {
                try { DocumentsContract.renameDocument(getContentResolver(), backup, fileName); } catch (Exception ignored) { }
            }
            throw new IllegalStateException("تأیید نهایی فایل بایگانی ناموفق بود.");
        }
        deleteIfExists(backup);
        return "OK";
    }

    private void notifyFolderResult(boolean ok, String message) {
        if (web == null) return;
        String js = "if(window.onNativeArchiveFolderResult){window.onNativeArchiveFolderResult(" +
                (ok ? "true" : "false") + "," + JSONObject.quote(message == null ? "" : message) + ");}";
        new Handler(Looper.getMainLooper()).post(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ARCHIVE_TREE_REQ) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(tree, flags);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(TREE_URI_KEY, tree.toString()).apply();
                    archiveDirectory(true);
                    notifyFolderResult(true, "پوشه بایگانی انتخاب شد؛ انتقال امن اطلاعات قدیمی آغاز می‌شود.");
                } catch (Exception e) {
                    notifyFolderResult(false, "ذخیره مجوز پوشه ناموفق بود: " + safeMessage(e));
                }
            } else {
                notifyFolderResult(false, "انتخاب پوشه لغو شد؛ اطلاعات برنامه حذف نشد.");
            }
            return;
        }
        if (requestCode == FILE_REQ && filePathCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    public class ArchiveBridge {
        @JavascriptInterface
        public void pickArchiveFolder() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(intent, ARCHIVE_TREE_REQ);
                } catch (Exception e) {
                    notifyFolderResult(false, "باز کردن انتخاب‌گر پوشه ناموفق بود: " + safeMessage(e));
                }
            });
        }

        @JavascriptInterface
        public boolean hasFolder() {
            Uri tree = savedTreeUri();
            if (tree == null) return false;
            try { return archiveDirectory(false) != null; }
            catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String writeArchiveFile(String fileName, String content) {
            try { return writeArchive(fileName, content == null ? "" : content); }
            catch (Exception e) { return "ERROR:" + safeMessage(e); }
        }

        @JavascriptInterface
        public String readArchiveFile(String fileName) {
            try {
                if (fileName == null || !ARCHIVE_FILE.matcher(fileName).matches()) return "ERROR:نام فایل معتبر نیست.";
                Uri tree = savedTreeUri();
                if (tree == null) return "";
                Uri dir = archiveDirectory(false);
                if (dir == null) return "";
                Uri file = findChild(tree, dir, fileName);
                if (file == null) return "";
                return readDocument(file);
            } catch (Exception e) { return "ERROR:" + safeMessage(e); }
        }

        @JavascriptInterface
        public String listArchiveFiles() {
            try {
                JSONArray names = new JSONArray();
                Uri tree = savedTreeUri();
                if (tree == null) return names.toString();
                Uri dir = archiveDirectory(false);
                if (dir == null) return names.toString();
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir));
                String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
                try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
                    if (cursor != null) {
                        int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                        while (cursor.moveToNext()) {
                            String name = cursor.getString(nameCol);
                            if (name != null && ARCHIVE_FILE.matcher(name).matches()) names.put(name);
                        }
                    }
                }
                return names.toString();
            } catch (Exception e) { return "ERROR:" + safeMessage(e); }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}