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
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.MapViewPosition;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import org.mapsforge.map.util.MapViewProjection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends BaseActivity {
    static final String TAG = "MainActivity";
    static final String ACTION_FLEET_RECEIVER_LOG_MESSAGE = "FLEET_RECEIVER_LOG_MESSAGE";
    static final String EXTRA_LOG_MESSAGE = "LOG_MESSAGE";
    static final long DISPLAY_INTERVAL_MILLIS = 5*1000;
    static final int MAX_ZOOM_IN_LEVEL = 14;

    private LogMessageReceiver mLogMessageReceiver = new LogMessageReceiver();
    private PointsAddedReceiver mPointsAddedReceiver = new PointsAddedReceiver();
    private AppDatabase mDb = AppDatabase.getDatabase(this);
    private MapView mMapView;
    private Map<String, Marker> mMarkers = new HashMap<>();
    private Map<String, TextView> mLabels = new HashMap<>();
    private String mSelectedReporterId = null;
    private Handler mHandler = null;
    private Runnable mRunnable = null;
    private Marker mSelectionMarker = null;

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
        startService(new Intent(getApplicationContext(), ReceiverService.class));
        registerReceiver(mLogMessageReceiver, new IntentFilter(ACTION_FLEET_RECEIVER_LOG_MESSAGE));
        registerReceiver(mPointsAddedReceiver, new IntentFilter(ReceiverService.ACTION_FLEET_RECEIVER_POINTS_ADDED));

        findViewById(R.id.zoom_points).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                zoomToAllPoints(mMapView);
            }
        });

        // Some elements of the display show elapsed time, so we need to
        // periodically update the display even if there are no new events.
        mHandler = new Handler();
        mRunnable = new Runnable() {
            public void run() {
                updateMarkers();
                updateReporterFrame();
                mHandler.postDelayed(mRunnable, DISPLAY_INTERVAL_MILLIS);
            }
        };
    }

    @Override protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRunnable, 0);
    }

    @Override protected void onPause() {
        mHandler.removeCallbacks(mRunnable);
        super.onPause();
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
        final MapView mapView = (MapView) findViewById(id);
        TileCache tileCache = AndroidUtil.createTileCache(this, "mapcache",
            mapView.getModel().displayModel.getTileSize(), 1f,
            mapView.getModel().frameBufferModel.getOverdrawFactor());
        final MultiMapDataStore multiMap = new MultiMapDataStore(
            MultiMapDataStore.DataPolicy.RETURN_ALL);
        addMapFile(multiMap, getAssetFile("world.map"));
        addMapFilesInDir(multiMap, Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS));
        TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCache, multiMap,
            mapView.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

        mapView.getLayerManager().getLayers().add(tileRendererLayer);
        mapView.setClickable(true);
        mapView.getMapScaleBar().setVisible(true);
        mapView.setBuiltInZoomControls(true);
        mapView.getMapZoomControls().getChildAt(0).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                zoom(-1);
            }
        });
        mapView.getMapZoomControls().getChildAt(1).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                zoom(1);
            }
        });
        mapView.post(new Runnable() {
            @Override public void run() {
                if (!zoomToAllPoints(mapView)) {
                    mapView.setCenter(multiMap.startPosition());
                    mapView.setZoomLevel(multiMap.startZoomLevel());
                }
            }
        });
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

    boolean zoomToAllPoints(MapView mapView) {
        List<LatLong> positions = new ArrayList<>();
        for (ReporterEntity.WithPoint rp : mDb.getReporterDao().getAllActiveWithLatestPoints()) {
            if (rp.point != null) positions.add(new LatLong(rp.point.latitude, rp.point.longitude));
        }
        if (positions.size() > 0) {
            BoundingBox bounds = new BoundingBox(positions);
            mapView.setCenter(bounds.getCenterPoint());
            int zoomLevel = mapView.getModel().mapViewPosition.getZoomLevel();
            int tileSize = mapView.getModel().displayModel.getTileSize();
            // The idea of zoomLimit is to avoid zooming in past MAX_ZOOM_IN_LEVEL,
            // but if already zoomed in beyond that, avoid needlessly zooming out.
            int zoomLimit = Math.max(zoomLevel, MAX_ZOOM_IN_LEVEL);
            mapView.setZoomLevel(positions.size() == 1 ? (byte) zoomLimit :
                (byte) Math.min(zoomLimit, LatLongUtils.zoomForBounds(
                    mapView.getDimension(), bounds, tileSize
                ))
            );
            return true;
        }
        return false;
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
            updateReporterFrame();
        }
    }

    void updateMarkers() {
        for (ReporterEntity.WithPoint rp : mDb.getReporterDao().getAllActiveWithLatestPoints()) {
            if (rp.point == null) continue;

            LatLong position = new LatLong(rp.point.latitude, rp.point.longitude);
            Marker marker = mMarkers.get(rp.reporter.reporterId);
            if (marker == null) {
                marker = new ReporterMarker(rp.reporter.reporterId, position);
                mMarkers.put(rp.reporter.reporterId, marker);
                mMapView.addLayer(marker);
            } else {
                marker.setLatLong(position);
                if (rp.reporter.reporterId.equals(mSelectedReporterId)) {
                    mSelectionMarker.setLatLong(position);
                }
            }

            TextView label = mLabels.get(rp.reporter.reporterId);
            if (label == null) {
                label = new TextView(this);
                label.setTextColor(0xffffffff);
                label.setText(rp.reporter.label);
                label.setTextSize(16);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                label.setShadowLayer(12, 0, 0, 0xff000000);
                label.setPadding(0, 12, 0, 0);
                label.setLayoutParams(new MapView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    position, MapView.LayoutParams.Alignment.TOP_CENTER
                ));
                mLabels.put(rp.reporter.reporterId, label);
                mMapView.addView(label);
            } else {
                label.setLayoutParams(new MapView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    position, MapView.LayoutParams.Alignment.TOP_CENTER
                ));
            }
        }
        mMapView.getLayerManager().redrawLayers();
    }

    void toggleSelectReporter(String reporterId) {
        if (reporterId.equals(mSelectedReporterId)) {
            mSelectedReporterId = null;
            if (mSelectionMarker != null) {
                mMapView.getLayerManager().getLayers().remove(mSelectionMarker);
            }
            mMapView.getModel().mapViewPosition.setPivot(null);
        } else {
            mSelectedReporterId = reporterId;
            LatLong position = mMarkers.get(reporterId).getPosition();
            if (mSelectionMarker == null) {
                mSelectionMarker = new Marker(
                    position,
                    AndroidGraphicFactory.convertToBitmap(getResources().getDrawable(R.drawable.select)),
                    0, 0);
            }
            mSelectionMarker.setLatLong(position);
            mMapView.getModel().mapViewPosition.setPivot(position);
            // MapView.addLayer is stupid and will crash if we re-add an existing layer.
            if (!mMapView.getLayerManager().getLayers().contains(mSelectionMarker)) {
                mMapView.addLayer(mSelectionMarker);
            }
        }
        updateReporterFrame();
    }

    void updateReporterFrame() {
        if (mSelectedReporterId == null) {
            u.showFrameChild(R.id.reporter_summary);
            u.setText(R.id.registered_count, "" + mDb.getReporterDao().getAllActive().size());
            long oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000;
            u.setText(R.id.reported_count, "" + mDb.getReporterDao().getAllReportedSince(oneHourAgo).size());
        } else {
            u.showFrameChild(R.id.reporter_details);
            ReporterEntity r = mDb.getReporterDao().get(mSelectedReporterId);
            PointEntity p = mDb.getPointDao().getLatestPointForReporter(mSelectedReporterId);
            int motionColor = p.isResting() ? 0xffe04020 : 0xff00a020;
            u.setText(R.id.speed, Utils.format("%.0f km/h", p.speedKmh, motionColor));
            u.setText(R.id.speed_details, Utils.format("as of " + Utils.describeTime(p.timeMillis)));
            u.setText(R.id.motion, Utils.describePeriod(p.getSegmentMillis()), motionColor);
            u.setText(R.id.motion_details, p.isResting() ? "stopped at this spot" : "since last stop");
        }
    }

    /** Zooms the map while holding the selected marker in a fixed position. */
    void zoom(int change) {
        MapViewPosition pos = mMapView.getModel().mapViewPosition;
        byte zoom = pos.getZoomLevel();
        byte newZoom = (byte) (zoom + change);
        if (newZoom < pos.getZoomLevelMin()) newZoom = pos.getZoomLevelMin();
        if (newZoom > pos.getZoomLevelMax()) newZoom = pos.getZoomLevelMax();
        change = newZoom - zoom;

        Marker marker = mMarkers.get(mSelectedReporterId);
        if (marker == null) {
            pos.setZoomLevel(newZoom, true);
            return;
        }
        MapViewProjection proj = mMapView.getMapViewProjection();
        Point centerPoint = proj.toPixels(pos.getCenter());
        Point pivotPoint = proj.toPixels(marker.getLatLong());
        double dx = centerPoint.x - pivotPoint.x;
        double dy = centerPoint.y - pivotPoint.y;
        double factor = 1 - Math.pow(0.5, change);
        pos.moveCenterAndZoom(dx * factor, dy * factor, (byte) change, false);
    }

    class ReporterMarker extends Marker {
        private String mReporterId;
        private double mTapRadius;

        ReporterMarker(String mReporterId, LatLong position) {
            super(position, AndroidGraphicFactory.convertToBitmap(getResources().getDrawable(R.drawable.marker)), 0, 0);
            this.mReporterId = mReporterId;
            this.mTapRadius = getBitmap().getWidth() * 0.8;
        }

        public boolean onTap(LatLong tap, Point layerPoint, Point tapPoint) {
            if (Math.abs(layerPoint.x - tapPoint.x) < mTapRadius &&
                Math.abs(layerPoint.y - tapPoint.y) < mTapRadius) {
                toggleSelectReporter(mReporterId);
                return true;
            }
            return false;
        }
    }
}
