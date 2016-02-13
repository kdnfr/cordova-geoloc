package fr.dijkman.pgs;

import org.json.*;
import java.util.*;

import android.util.Log;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Environment;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.app.Service;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.widget.Toast;

import android.app.Notification;


import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.media.AudioManager;
import android.media.ToneGenerator;

import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;

import android.database.sqlite.*;
import android.database.Cursor;
import android.content.ContentValues;
import com.loopj.android.http.*;
import org.apache.http.Header;


import fr.dijkman.pgs.constant.Constant;


public class PassiveGeolocationService extends Service implements LocationListener {

    static final String TAG = PassiveGeolocationService.class.getCanonicalName();
    static final String PREFS_NAME = "PassiveGeolocationService";

    protected ToneGenerator toneGenerator;

    Boolean isDebug;
    Long minTime;
    Float minDistance;
    Float desiredAccuracy;
    Float distanceFilter;
    Long minUploadInterval;
    Long lastUploadTime;
    String appLocalUID;
    Boolean uploadOldByCell;
    Long maxIdleTime;
    String apiURL;


    Location lastLocation = null;

    int currentNetworkType = ConnectivityManager.TYPE_DUMMY;
    int lastNetworkType = ConnectivityManager.TYPE_DUMMY;
    String currentNetworkTypeName = "DUMMY";
    String lastNetworkTypeName = "DUMMY";

    SharedPreferences pref;
    LocationManager locationManager;

    static SQLiteDatabase db = null;





    @Override
    public void onCreate() {
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);


        BootReceiver.startAlarm(this);

        pref = this.getSharedPreferences(PREFS_NAME, 0 | Context.MODE_MULTI_PROCESS);

        minTime = pref.getLong("minTime", 0);
        minDistance = pref.getFloat("minDistance", 10);
        desiredAccuracy = pref.getFloat("desiredAccuracy", 100);
        distanceFilter = pref.getFloat("distanceFilter", 0);
        isDebug = pref.getBoolean("debug", false);
        minUploadInterval = pref.getLong("minUploadInterval", 5 * 60 * 1000);
        appLocalUID = pref.getString("appLocalUID", "");
        uploadOldByCell = pref.getBoolean("uploadOldByCell", false);
        maxIdleTime = pref.getLong("maxIdleTime", 10 * 60 * 1000);

        apiURL = "http://www.dijkman.fr/cnxLocation";


        lastUploadTime = 0L;


        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mNetworkChangedReceiver, filter);


        Log.i(TAG, "onCreate,  desiredAccuracy=" + desiredAccuracy + ", minDistance=" + minDistance + ", minTime=" + minTime + ", distanceFilter=" + distanceFilter + ", isDebug=" + isDebug);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Android before JellyBean may ignore minTime and minDistance
        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, minTime, minDistance, this);
    }

    public void locationHandler(Location location) {
        Log.d(TAG, "locationHandler fired, location=" + location + ", lastLocation=" + lastLocation);


        final long locationTime = location.getTime();
        final double longitude = location.getLongitude();
        final double latitude = location.getLatitude();
        final float accuracy = location.getAccuracy();


        Long currentTime = location.getTime();
        Long lastTime = 0L;
        if (lastLocation != null) {
            lastTime = lastLocation.getTime();
        }
        Long timeElapsed = currentTime - lastTime;


        Boolean shouldSave = true;
        do {
            /// ??????????????(maxIdleTime)
            if (timeElapsed >= maxIdleTime) {
                if (isDebug) {
                    Toast.makeText(this, "force update (timeElapsed)" + timeElapsed + ">(maxIdleTime)" + maxIdleTime, Toast.LENGTH_LONG).show();
                    startTone("chirp_chirp_chirp");
                }

                Log.d(TAG, "?????????????? " + (timeElapsed / 1000) + " ?(>=" + (maxIdleTime / 1000) + ")??????");
                shouldSave = true;
                break;
            }
            else {
                Log.d(TAG, "?????????????? " + (timeElapsed / 1000) + " ?(<" + (maxIdleTime / 1000) + "),????");
            }


            /// Is accuracy < desiredAccuracy
            if (desiredAccuracy > 0 && accuracy > desiredAccuracy) {
                if (isDebug) {
                    Toast.makeText(this, "no acy, (loc)" + accuracy + ">(desired)" + desiredAccuracy, Toast.LENGTH_LONG).show();
                    startTone("doodly_doo");
                }

                Log.d(TAG, "locationHandler, reject by desiredAccuracy: (loc)" + accuracy + ">(desired)" + desiredAccuracy);

                shouldSave = false;
                break;
            }



            /// Is distance > distanceFilter
            if (lastLocation != null) {
                float distance = lastLocation.distanceTo(location);
                float lastAccuracy = lastLocation.getAccuracy();

                Log.d(TAG, "locationHandler, distance to lastLocation is " + distance);

                if (distance <= distanceFilter) {
                    if (isDebug) {
                        Toast.makeText(this, "dis filter, (loc)" + distance + "<=(filter)" + distanceFilter + ", // (loc acy)" + accuracy + ">=(last acy)" + lastAccuracy, Toast.LENGTH_LONG).show();
                        startTone("long_beep");
                    }

                    Log.d(TAG, "locationHandler, reject by distanceFilter: (loc)" + distance + "<=(filter)" + distanceFilter + ", // (loc acy)" + accuracy + ">=(last acy)" + lastAccuracy);

                    shouldSave = false;
                    break;
                }
            }
        }
        while (false);



        if (shouldSave == true) {
            Log.d(TAG, "???????????????????: " + location.toString());

            if (isDebug) {
                startTone("beep");
                Toast.makeText(this, "located: acy=" + accuracy + ", lat=" + latitude + ", log=" + longitude, Toast.LENGTH_LONG).show();
            }

            lastLocation = location;
            saveLocation(location);
        }
        else {
            Log.d(TAG, "??????????????????????????: " + location.toString());

            /*
            if (isDebug) {
                startTone("long_beep");
            }
            */
        }
    }



    /**
     * ???????????
     */
    protected void saveLocation(Location loc) {
        Location[] locs = {loc};

        saveLocations(locs);
    }

    protected void saveLocations(Location[] locs) {
        Long currentTime = System.currentTimeMillis();

        if (currentTime - lastUploadTime < minUploadInterval) {
            Log.d(TAG, "??????????????? " + ((minUploadInterval - (currentTime - lastUploadTime)) / 1000) + "???????????????????: " + locs.toString());
            storeLocations(locs);
            return;
        }


        /// ??????????
        Location newLocs[];
        Boolean wifiOn = isWiFi();
        if (uploadOldByCell || wifiOn) {
            Log.d(TAG, "????????, uploadOldByCell=" + uploadOldByCell + ", WiFi on = " + wifiOn);
            Location oldLocs[] = fetchLatestLocations(10);

            newLocs = new Location[oldLocs.length + locs.length];
            for (int i = 0; i < oldLocs.length; i++) {
                newLocs[i] = oldLocs[i];
            };

            for (int i = oldLocs.length, k = 0; i < oldLocs.length + locs.length; i++, k++) {
                newLocs[i] = locs[k];
            }
        }
        else {
            newLocs = locs;
        }


        Log.d(TAG, "? " + newLocs.length + " ???????????: " + newLocs.toString());
        uploadLocations(newLocs);
    }




    protected void uploadLocations(final Location[] locs) {
        AsyncHttpClient httpClient;

        httpClient = new AsyncHttpClient();
        httpClient.setMaxRetriesAndTimeout(1, 30 * 1000);
        httpClient.setTimeout(30 * 1000);



        RequestParams params = new RequestParams();
        params.put("locations", locs2JSONStr(locs));

        final String requestMsg = String.format(
            "http request url: %s, with params: %s",
            apiURL, params.toString()
        );

        lastUploadTime = System.currentTimeMillis();

        httpClient.post(apiURL, params, new AsyncHttpResponseHandler() {
            @Override
            public void onStart() {
                Log.d(TAG, "httpClient.post.onStart, " + requestMsg);
            }

            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] response) {
                Log.d(TAG, "httpClient.post.onSuccess");


                try {
                    String stringResponse = new String(response, "UTF-8");
                    if (stringResponse.length() == 0) {
                        Log.w(TAG, "?????????????");
                        return;
                    }

                    if (stringResponse.compareTo("ok") == 0) {
                        Log.d(TAG, "????? " + locs.length + " ?????");
                    }
                    else {
                        /// ????
                        Log.i(TAG, "?? + " + locs.length + " ?????????????????????????????`" + stringResponse + "'");
                        storeLocations(locs);
                    }
                }
                catch (java.io.UnsupportedEncodingException ex) {
                    storeLocations(locs);
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] error, Throwable ex) {
                String err = "";
                try {
                    err = new String(error, "UTF-8");
                }
                catch (java.io.UnsupportedEncodingException ex2) {
                    err = error.toString();
                }
                catch (java.lang.NullPointerException e2) {
                    err = "error ????";
                }
                Log.d(TAG, "(onFailure by LocationUpdate)???????????????????: " + ex);

                storeLocations(locs);
            }

            @Override
            public void onRetry(int retryNo) {
                Log.w(TAG, "httpClient.post.onRetry, http request url: " + requestMsg);
            }
        });
    }


    protected SQLiteDatabase getDB() {

        try {
            if (db == null) {
                String dbPath = this.getDir("", Context.MODE_PRIVATE).toString() + "/locs.sqlite";

                Log.d(TAG, " dbPath`" + dbPath + "' ");

                db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
                db.execSQL("CREATE TABLE IF NOT EXISTS `b_location` (" +
        "`location_id`	INTEGER PRIMARY KEY AUTOINCREMENT," +
        "`latitude`	TEXT," +
        "`longitude`	TEXT," +
        "`altitude`	TEXT," +
        "`accuracy`	TEXT," +
        "`rtime`	BIGINT," +
        "`ctime`	BIGINT, " +
        "`src`	TEXT " +
    ");  " +
    "CREATE INDEX idx_b_location_rtime IF NOT EXISTS ON b_location(rtime); "
                );
            }
        }
        catch (java.lang.IllegalStateException e) {
            Log.w(TAG, "IllegalStateException: " + e);
        }



        return db;
    }



    protected void storeLocations(Location[] locs) {
        long ret;

        for (int i = 0; i < locs.length; i++) {
            ContentValues data = loc2ContentValues(locs[i]);
            data.put("ctime", System.currentTimeMillis());

            ret = getDB().insertOrThrow("b_location", null, data);
            if (ret == -1) {
                Log.i(TAG, "b_location: " + locs[i].toString());
            }
            else {
                Log.i(TAG, "b_location " + ret + " : " + locs[i].toString());
            }
        }

        Log.i(TAG, locs.length + " locs");
    }



    protected ContentValues loc2ContentValues(Location loc) {
        ContentValues ret = new ContentValues();

        Double latitude = loc.getLatitude();
        Double longitude = loc.getLongitude();
        Double altitude = loc.getAltitude();
        Float accuracy = loc.getAccuracy();



        ret.put("latitude", latitude.toString());
        ret.put("longitude", longitude.toString());
        ret.put("altitude", altitude.toString());
        ret.put("accuracy", accuracy.toString());
        ret.put("rtime", loc.getTime());
        ret.put("src", loc.getProvider());

        return ret;
    }


    protected Location[] fetchLatestLocations(int num) {
        Cursor cur;
        SQLiteDatabase db;
        int ret;

        db = getDB();

        String cols[] = {"location_id", "latitude", "longitude", "altitude", "accuracy", "rtime", "src"};
        String selection = null;
        String selectionArgs[] = null;
        String groupBy = null;
        String having = null;
        String orderBy = "rtime DESC";
        String limit = String.format("%d", num);

        cur = db.query("b_location", cols, selection, selectionArgs, groupBy, having, orderBy, limit);

        Log.d(TAG, String.format("?????? %d ???", cur.getCount()));

        Location[] locs = new Location[cur.getCount()];
        int ids[] = new int[cur.getCount()];

        cur.moveToFirst();
        for (int i = 0; i < cur.getCount(); i++) {
            Log.d(TAG, "??? " + i + " ???");
            locs[i] = new Location(cur.getString(cur.getColumnIndex("src")));
            locs[i].setLatitude(Double.parseDouble(cur.getString(cur.getColumnIndex("latitude"))));
            locs[i].setLongitude(Double.parseDouble(cur.getString(cur.getColumnIndex("longitude"))));
            locs[i].setAltitude(Double.parseDouble(cur.getString(cur.getColumnIndex("altitude"))));
            locs[i].setAccuracy(Float.parseFloat(cur.getString(cur.getColumnIndex("accuracy"))));
            locs[i].setTime(cur.getLong(cur.getColumnIndex("rtime")));

            ids[i] = cur.getInt(cur.getColumnIndex("location_id"));

            cur.moveToNext();
        }

        cur.close();


        for (int i = 0; i < ids.length; i++) {
            ret = db.delete("b_location", String.format("location_id=%d", ids[i]), null);
            Log.d(TAG, "????????location_id=" + ids[i] + ", ??? " + ret + " ?");
        }

        return locs;
    }



    protected String locs2JSONStr(Location[] locs) {
        String ret;

        if (locs.length <= 0) {
            return "[]";
        }

        String token[] = new String[locs.length];
        for (int i = 0; i < locs.length; i++) {
            Double latitude = locs[i].getLatitude();
            Double longitude = locs[i].getLongitude();
            Double altitude = locs[i].getAltitude();
            Float accuracy = locs[i].getAccuracy();

            token[i] = String.format("{\"latitude\": \"%s\", \"longitude\": \"%s\", \"altitude\": \"%s\", \"accuracy\": \"%s\", \"src\": \"%s\", \"time\": %d}", latitude.toString(), longitude.toString(), altitude.toString(), accuracy.toString(), locs[i].getProvider(), locs[i].getTime());
        }


        /// ????? join() ??????????????????????????????????
        ret = "[ ";
        for (int i = 0; i < token.length - 1; i++) {
            ret += token[i];
            ret += ", ";
        }
        ret += token[token.length - 1];
        ret += " ]";


        Log.d(TAG, "????? JSON Location ??: " + ret);

        return ret;
    }


    /**
     * ? WiFi ?????????????????
     */
    protected void uploadOldLocationsByWiFi() {
        if (!isWiFi()) {
            Log.d(TAG, "?????? WiFi?????????????");
            return;
        }
        else {
            Log.d(TAG, "????? WiFi???????????");
        }


        final Location locs[] = fetchLatestLocations(50);
        if (locs.length <= 0) {
            Log.d(TAG, "???????????????? WiFi ???????");
            return;
        }


        /// ????
        /// FIXME: ???????????????????uploadOldLocationsByWiFi() ????
        /// FIXME: ????????????????????? uploadOldLocationsByWiFi()???????? AsyncHttpResponseHandler ???
        AsyncHttpClient httpClient;

        httpClient = new AsyncHttpClient();
        httpClient.setMaxRetriesAndTimeout(1, 30 * 1000);
        httpClient.setConnectTimeout(30 * 1000);



        RequestParams params = new RequestParams();
        params.put("locations", locs2JSONStr(locs));

        final String requestMsg = String.format(
            "http request url: %s, with params: %s",
            apiURL, params.toString()
        );

        httpClient.post(apiURL, params, new AsyncHttpResponseHandler() {
            @Override
            public void onStart() {
                Log.d(TAG, "httpClient.post.onStart, " + requestMsg);
            }

            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] response) {
                Log.d(TAG, "httpClient.post.onSuccess");


                try {
                    String stringResponse = new String(response, "UTF-8");
                    if (stringResponse.length() == 0) {
                        Log.w(TAG, "?????????????");
                        return;
                    }

                    if (stringResponse.compareTo("ok") == 0) {
                        Log.d(TAG, "????? " + locs.length + " ???????");
                        uploadOldLocationsByWiFi();
                    }
                    else {
                        /// ????
                        Log.i(TAG, "?? + " + locs.length + " ???????????????????????????????`" + stringResponse + "'");
                        storeLocations(locs);
                    }
                }
                catch (java.io.UnsupportedEncodingException ex) {
                    storeLocations(locs);
                    //ex.printStackTrace();
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] error, Throwable ex) {
                String errstr;
                try {
                    errstr = new String(error, "UTF-8");
                }
                catch (java.io.UnsupportedEncodingException e) {
                    errstr = "(???? error ???)";
                }
                catch (java.lang.NullPointerException e2) {
                    errstr = "error ????";
                }
                Log.d(TAG, "httpClient.post.onFailure (byWiFi)");
                Log.d(TAG, "(onFailure, byWiFi)??????????????????????????: " + ex);

                storeLocations(locs);
            }

            @Override
            public void onRetry(int retryNo) {
                Log.w(TAG, "httpClient.post.onRetry, http request url: " + requestMsg);
            }
        });


    }



	public static JSONObject toJSONObject(Location location) throws JSONException {
		JSONObject json = new JSONObject();
		json.put("time", location.getTime());
		json.put("latitude", location.getLatitude());
		json.put("longitude", location.getLongitude());
		json.put("accuracy", location.getAccuracy());
		json.put("speed", location.getSpeed());
		json.put("altitude", location.getAltitude());
		json.put("bearing", location.getBearing());
		return json;
	}


    protected boolean isWiFi() {
        ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetInfo = cm.getActiveNetworkInfo();
        if (activeNetInfo != null && activeNetInfo.getType() == ConnectivityManager.TYPE_WIFI) {
            return true;
        }
        else {
            return false;
        }
    }


    private static boolean isWifi(Context mContext) {
		ConnectivityManager connectivityManager = (ConnectivityManager) mContext
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetInfo = connectivityManager.getActiveNetworkInfo();
		if (activeNetInfo != null
				&& activeNetInfo.getType() == ConnectivityManager.TYPE_WIFI) {
			return true;
		}
		return false;
	}


    protected void startTone(String name) {
        int tone = 0;
        int duration = 1000;

        if (name.equals("beep")) {
            tone = ToneGenerator.TONE_PROP_BEEP;
        } else if (name.equals("beep_beep_beep")) {
            tone = ToneGenerator.TONE_CDMA_CONFIRM;
        } else if (name.equals("long_beep")) {
            tone = ToneGenerator.TONE_CDMA_ABBR_ALERT;
        } else if (name.equals("doodly_doo")) {
            tone = ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
        } else if (name.equals("chirp_chirp_chirp")) {
            tone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
        } else if (name.equals("dialtone")) {
            tone = ToneGenerator.TONE_SUP_RINGTONE;
        }
        toneGenerator.startTone(tone, duration);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            locationHandler(location);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        if (db != null) {
            //db.close();
            //db = null;
        }

        unregisterReceiver(mNetworkChangedReceiver);

        locationManager.removeUpdates(this);

        super.onDestroy();
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.v(TAG, "onProviderDisabled, disabled: " + provider);
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.v(TAG, "onProviderEnabled, enabled: " + provider);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.v(TAG, String.format(
            "onStatusChanged, provider: %s status: %i extars: %s",
            provider, status, extras)
        );
    }


    private BroadcastReceiver mNetworkChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connectivityManager;
            NetworkInfo info;
            String action = intent.getAction();

            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
                info = connectivityManager.getActiveNetworkInfo();

                lastNetworkType = currentNetworkType;
                lastNetworkTypeName = currentNetworkTypeName;
                if (info == null) {
                    currentNetworkType = ConnectivityManager.TYPE_DUMMY;
                    currentNetworkTypeName = "DUMMY";
                }
                else {
                    currentNetworkType = info.getType();
                    currentNetworkTypeName = info.getTypeName();
                }


                Log.d(TAG, "????? " + lastNetworkTypeName + " ?? " + currentNetworkTypeName);

                if (lastNetworkType != ConnectivityManager.TYPE_WIFI && currentNetworkType == ConnectivityManager.TYPE_WIFI) {
                    Log.d(TAG, "WiFi ?????????????");
                    uploadOldLocationsByWiFi();
                }
            }
        }
    };
}
