package ca.zesty.fleetreceiver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.TextView;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;


public class MainActivity extends BaseActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_FLEET_RECEIVER_LOG_MESSAGE = "FLEET_RECEIVER_LOG_MESSAGE";
    static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";

    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private PointsAddedReceiver mPointsAddedReceiver = new PointsAddedReceiver();
    private AppDatabase mDb = AppDatabase.getDatabase(this);
    private MapView mMapView;
    private Map<String, Marker> mMarkers = new HashMap<>();
    private Map<String, TextView> mLabels = new HashMap<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Fleet Receiver " + BuildConfig.VERSION_NAME);

        ActivityCompat.requestPermissions(this, new String[] {
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WAKE_LOCK
        }, 0);

        AndroidGraphicFactory.createInstance(getApplication());
        mMapView = initializeMap(R.id.map);
        updateMarkers();
        startService(new Intent(getApplicationContext(), ReceiverService.class));
        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_RECEIVER_LOG_MESSAGE));
        registerReceiver(mPointsAddedReceiver, new IntentFilter(ReceiverService.ACTION_FLEET_RECEIVER_POINTS_ADDED));
    }

    @Override protected void onDestroy() {
        mMapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(
        int requestCode, String permissions[], int[] grantResults) {
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, 0);
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_registration) {
            startActivity(new Intent(this, RegistrationActivity.class));
        }
        if (item.getItemId() == R.id.action_add_map_data) {
            u.showMessageBox("Add map data",
                "To add data to the map, please download any MapsForge file " +
                "with a filename ending in \".map\" and leave it in your " +
                "Download folder.\n\n" +
                "This map will load and show the information from all the " +
                "\".map\" files in your Download folder.");
        }
        return false;
    }

    MapView initializeMap(int id) {
        MapView mapView = (MapView) findViewById(id);
        TileCache tileCache = AndroidUtil.createTileCache(this, "mapcache",
            mapView.getModel().displayModel.getTileSize(), 1f,
            mapView.getModel().frameBufferModel.getOverdrawFactor());
        MultiMapDataStore multiMap = new MultiMapDataStore(
            MultiMapDataStore.DataPolicy.RETURN_ALL);
        addMapFile(multiMap, getAssetFile("world.map"));
        addMapFilesInDir(multiMap, Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS));
        TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCache, multiMap,
            mapView.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

        mapView.getLayerManager().getLayers().add(tileRendererLayer);
        mapView.setCenter(multiMap.startPosition());
        mapView.setZoomLevel(multiMap.startZoomLevel());
        mapView.setClickable(true);
        mapView.getMapScaleBar().setVisible(true);
        mapView.setBuiltInZoomControls(true);
        return mapView;
    }

    void addMapFilesInDir(MultiMapDataStore multiMap, File dir) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                addMapFilesInDir(multiMap, file);
            }
            if (file.isFile() && file.getName().toLowerCase().endsWith(".map")) {
                addMapFile(multiMap, file);
            }
        }
    }

    void addMapFile(MultiMapDataStore multiMap, File file) {
        try {
            MapDataStore map = new MapFile(file);
            multiMap.addMapDataStore(map, true, true);
            Log.i(TAG, "Loaded map from " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Could not load map from " + file.getAbsolutePath(), e);
            e.printStackTrace();
        }
    }

    File getAssetFile(String name) {
        File destination = new File(getCacheDir(), name);
        if (!destination.exists()) {
            try {
                InputStream in = getAssets().open(name);
                OutputStream out = new FileOutputStream(destination);
                byte[] buffer = new byte[8192];
                int count = in.read(buffer);
                while (count >= 0) {
                    out.write(buffer, 0, count);
                    count = in.read(buffer);
                }
                out.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not copy asset to " + destination, e);
            }
        }
        return destination;
    }

    class LogMessageReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(EXTRA_LOG_MESSAGE)) {
                String message = intent.getStringExtra(EXTRA_LOG_MESSAGE);
                ((TextView) findViewById(R.id.message_log)).append(message + "\n");
            }
        }
    }

    public static void postLogMessage(Context context, String message) {
        Intent intent = new Intent(ACTION_FLEET_RECEIVER_LOG_MESSAGE);
        intent.putExtra(EXTRA_LOG_MESSAGE,
            Utils.formatUtcTimeSeconds(System.currentTimeMillis()) + " - " + message);
        context.sendBroadcast(intent);
    }

    class PointsAddedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            updateMarkers();
        }
    }

    void updateMarkers() {
        Bitmap bitmap = AndroidGraphicFactory.convertToBitmap(getResources().getDrawable(R.drawable.marker));
        for (ReporterEntity.WithLatestPoint r : mDb.getReporterDao().getAllActiveWithLatestPoints()) {
            if (r.points == null || r.points.isEmpty()) continue;

            PointEntity point = r.points.get(0);
            LatLong position = new LatLong(point.latitude, point.longitude);
            Marker m = mMarkers.get(r.reporter.reporterId);
            if (m == null) {
                m = new Marker(position, bitmap, 0, 0);
                mMarkers.put(r.reporter.reporterId, m);
                mMapView.addLayer(m);

                TextView label = new TextView(this);
                label.setTextColor(0xffffffff);
                label.setText(r.reporter.label);
                label.setTextSize(16);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                label.setShadowLayer(12, 0, 0, 0xff000000);
                label.setPadding(0, 12, 0, 0);
                label.setLayoutParams(new MapView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    position, MapView.LayoutParams.Alignment.TOP_CENTER
                ));
                mLabels.put(r.reporter.reporterId, label);
                mMapView.addView(label);
            } else {
                m.setLatLong(position);
            }
        }
    }
}
