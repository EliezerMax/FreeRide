package com.labs.okey.freeride;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.afollestad.materialdialogs.AlertDialogWrapper;
import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.gms.common.api.GoogleApiClient;
import com.labs.okey.freeride.adapters.WiFiPeersAdapter2;
import com.labs.okey.freeride.model.Join;
import com.labs.okey.freeride.model.WifiP2pDeviceUser;
import com.labs.okey.freeride.utils.BLEUtil;
import com.labs.okey.freeride.utils.ClientSocketHandler;
import com.labs.okey.freeride.utils.Globals;
import com.labs.okey.freeride.utils.GroupOwnerSocketHandler;
import com.labs.okey.freeride.utils.IRecyclerClickListener;
import com.labs.okey.freeride.utils.IRefreshable;
import com.labs.okey.freeride.utils.ITrace;
import com.labs.okey.freeride.utils.WAMSVersionTable;
import com.labs.okey.freeride.utils.WiFiUtil;
import com.microsoft.windowsazure.mobileservices.table.MobileServiceTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class PassengerRoleActivity extends BaseActivityWithGeofences
    implements ITrace,
        Handler.Callback,
        IRecyclerClickListener,
        IRefreshable,
        WiFiUtil.IPeersChangedListener,
        BLEUtil.IDeviceDiscoveredListener,
        WifiP2pManager.ConnectionInfoListener,
        WAMSVersionTable.IVersionMismatchListener{

    private static final String LOG_TAG = "FR.Passenger";

    TextView mTxtStatus;
    String mUserID;

    Boolean mDriversShown;
    TextView mTxtMonitorStatus;

    MobileServiceTable<Join> joinsTable;

    WiFiUtil wifiUtil;
    WiFiPeersAdapter2 mDriversAdapter;
    public List<WifiP2pDeviceUser> drivers = new ArrayList<>();

    private Handler handler = new Handler(this);
    public Handler getHandler() {
        return handler;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passenger_role);

        setupUI(getString(R.string.title_activity_passenger_role), "");
        wamsInit(true);

        mTxtStatus = (TextView)findViewById(R.id.txtStatusPassenger);

        joinsTable = getMobileServiceClient().getTable("joins", Join.class);

        wifiUtil = new WiFiUtil(this);

        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        mUserID = sharedPrefs.getString(Globals.USERIDPREF, "");

        new Thread() {
            @Override
            public void run(){

                try{

                    while (true) {

                        runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                String message = Globals.isInGeofenceArea() ?
                                        Globals.getMonitorStatus() :
                                        getString(R.string.geofence_outside);

                                mTxtMonitorStatus.setText(message);

                            }
                        });

                        Thread.sleep(1000);
                    }
                }
                catch(InterruptedException ex) {
                    Log.e(LOG_TAG, ex.getMessage());
                }

            }
        }.start();

    }

    protected void setupUI(String title, String subTitle){
        super.setupUI(title, subTitle);

        RecyclerView driversRecycler = (RecyclerView)findViewById(R.id.recyclerViewDrivers);
        driversRecycler.setHasFixedSize(true);
        driversRecycler.setLayoutManager(new LinearLayoutManager(this));
        driversRecycler.setItemAnimator(new DefaultItemAnimator());

        mDriversAdapter = new WiFiPeersAdapter2(this, R.layout.drivers_header, drivers);
        driversRecycler.setAdapter(mDriversAdapter);

        mDriversShown = false;

        mTxtMonitorStatus = (TextView)findViewById(R.id.status_monitor);
        Globals.setMonitorStatus(getString(R.string.geofence_outside));

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        //getMenuInflater().inflate(R.menu.menu_passenger_role, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        } else if ( id == R.id.action_camera_cv) {
            onCameraCV(null);
        }

        return super.onOptionsItemSelected(item);
    }

    public void onCameraCV(View view) {

        //String rideCode = ((TextView)findViewById(R.id.txtRideCode)).getText().toString();

        Intent intent = new Intent(this, CameraCVActivity.class);
        //intent.putExtra("rideCode", rideCode);
        startActivity(intent);
    }

    //
    // Implementation of IVersionMismatchListener
    //
    @Override
    public void mismatch(int majorLast, int minorLast, final String url) {
        try {
            new MaterialDialog.Builder(this)
                    .title(getString(R.string.new_version_title))
                    .content(getString(R.string.new_version_conent))
                    .positiveText(R.string.yes)
                    .negativeText(R.string.no)
                    .callback(new MaterialDialog.ButtonCallback() {
                        @Override
                        public void onPositive(MaterialDialog dialog) {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(Uri.parse(url));
                            //intent.setDataAndType(Uri.parse(url), "application/vnd.android.package-archive");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    })
                    .show();
        } catch (MaterialDialog.DialogException e) {
            // better that catch the exception here would be use handle to send events the activity
        }

    }

    @Override
    public void connectionFailure(Exception ex) {
        if( ex != null ) {

            View v = findViewById(R.id.drawer_layout);
            Snackbar.make(v, ex.getMessage(), Snackbar.LENGTH_LONG);
        }
    }

    @Override
    public void match() {

    }

    //
    // Implementation of IPeerClickListener
    //
    @Override
    public void clicked(View view, int position) {
        WifiP2pDeviceUser driverDevice = drivers.get(position);

        if( !Globals.isInGeofenceArea() ) {
            new MaterialDialog.Builder(this)
                    .title(R.string.geofence_outside_title)
                    .content(R.string.geofence_outside)
                    .positiveText(R.string.geofence_positive_answer)
                    .negativeText(R.string.geofence_negative_answer)
                    .callback(new MaterialDialog.ButtonCallback() {
                        @Override
                        public void onPositive(MaterialDialog dialog) {
                            Globals.setRemindGeofenceEntrance();
                        }
                    })
                    .show();
        } else {

            DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialogInterface, int which) {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        onSubmit();
                    }
                }
            };

            String message = getString(R.string.passenger_confirm) + driverDevice.deviceName;

            new AlertDialogWrapper.Builder(this)
                    .setTitle(message)
                    .setNegativeButton(R.string.no, dialogClickListener)
                    .setPositiveButton(R.string.yes, dialogClickListener)
                    .show();
        }

    }

    public void onSubmit() {
        onSubmitCode("");
    }

    public void onSubmitCode(final String rideCode){
        final String android_id = Settings.Secure.getString(this.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        final View v = findViewById(R.id.passenger_internal_layout);

        new AsyncTask<Void, Void, Void>() {

            Exception mEx;
            String mRideCode;

            @Override
            protected void onPostExecute(Void result){

                if( mEx != null ) {

                    String msg = mEx.getMessage();
                    String[] tokens = msg.split(":");
                    if(tokens.length > 1)
                        msg = tokens[1];

                    Snackbar snackbar =
                            Snackbar.make(v, msg, Snackbar.LENGTH_LONG);
                    snackbar.setAction(R.string.code_retry_action,
                            new View.OnClickListener(){
                                @Override
                                public void onClick(View v){
                                    showSubmitCodeDialog();
                                }
                            });
                    snackbar.setActionTextColor(getResources().getColor(R.color.white));
                    //snackbar.setDuration(8000);
                    snackbar.show();
                }
            }

            @Override
            protected Void doInBackground(Void... voids) {

                try{
                    Join _join  = new Join();
                    _join.setWhenJoined(new Date());
                    _join.setRideCode(rideCode);
                    _join.setDeviceId(android_id);

                    joinsTable.insert(_join).get();

                } catch(ExecutionException | InterruptedException ex ) {
                    mEx = ex;
                    Log.e(LOG_TAG, ex.getMessage());
                }

                return null;
            }
        }.execute();
    }

    private void showSubmitCodeDialog() {
        new MaterialDialog.Builder(this)
                .title(R.string.ride_code_title)
                .content(R.string.ride_code_dialog_content)
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_CLASS_NUMBER)
                .inputMaxLength(5)
                .input(R.string.ride_code_hint,
                        R.string.ride_code_refill,
                        new MaterialDialog.InputCallback() {
                            @Override
                            public void onInput(MaterialDialog dialog, CharSequence input) {
                                String rideCode = input.toString();
                                onSubmitCode(rideCode);
                            }
                        }

                ).show();
    }

    //
    // Implementation of IRefreshable
    //
    @Override
    public void refresh() {
        drivers.clear();
        mDriversAdapter.notifyDataSetChanged();

        final ImageButton btnRefresh = (ImageButton)findViewById(R.id.btnRefresh);
        btnRefresh.setVisibility(View.GONE);
        final ProgressBar progress_refresh = (ProgressBar)findViewById(R.id.progress_refresh);
        progress_refresh.setVisibility(View.VISIBLE);

        wifiUtil.startRegistrationAndDiscovery(this, mUserID);

        getHandler().postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        btnRefresh.setVisibility(View.VISIBLE);
                        progress_refresh.setVisibility(View.GONE);
                    }
                },
                5000);

    }

    @Override
    public boolean handleMessage(Message msg) {
        String strMessage;

        switch (msg.what) {
            case Globals.TRACE_MESSAGE:
                Bundle bundle = msg.getData();
                strMessage = bundle.getString("message");
                trace(strMessage);
                break;

            case Globals.MESSAGE_READ:
                byte[] buffer = (byte[] )msg.obj;
                strMessage = new String(buffer);
                trace(strMessage);
                break;
        }

        return true;

    }

    @Override
    public void trace(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if( mTxtStatus != null ) {
                    String current = mTxtStatus.getText().toString();
                    mTxtStatus.setText(current + "\n" + status);
                }
            }
        });
    }

    @Override
    public void alert(String message, final String actionIntent) {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialogInterface, int which) {
                if( which == DialogInterface.BUTTON_POSITIVE ) {
                    startActivity(new Intent(actionIntent));
                }
            }};

        new AlertDialogWrapper.Builder(this)
                .setTitle(message)
                .setNegativeButton(R.string.no, dialogClickListener)
                .setPositiveButton(R.string.yes, dialogClickListener)
                .show();
    }

    //
    // Implementations of WifiUtil.IPeersChangedListener
    //
    @Override
    public void add(final WifiP2pDeviceUser device) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mDriversAdapter.add(device);
                mDriversAdapter.notifyDataSetChanged();

                // remove 'type code' menu item
                mDriversShown = true;
                invalidateOptionsMenu();
            }
        });
    }

    //
    // Implementation of WifiP2pManager.ConnectionInfoListener
    //
    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo p2pInfo) {
        TextView txtMe = (TextView)findViewById(R.id.txtPassengerMe);
        Thread handler = null;

        if (p2pInfo.isGroupOwner) {
            txtMe.setText("ME: GroupOwner, Group Owner IP: " + p2pInfo.groupOwnerAddress.getHostAddress());
            try {
                handler = new GroupOwnerSocketHandler(this.getHandler());
                handler.start();
            } catch (IOException e){
                trace("Failed to create a server thread - " + e.getMessage());
            }

        } else {
            txtMe.setText("ME: NOT GroupOwner, Group Owner IP: " + p2pInfo.groupOwnerAddress.getHostAddress());

            handler = new ClientSocketHandler(
                    this.getHandler(),
                    p2pInfo.groupOwnerAddress,
                    this,
                    "!!!Message from PASSENGER!!!");
            handler.start();
            trace("Client socket opened.");

//            android.os.Handler h = new android.os.Handler();
//
//            Runnable r = new Runnable() {
//                @Override
//                public void run() {
//
//                    new WiFiUtil.ClientAsyncTask(context, p2pInfo.groupOwnerAddress,
//                                        "From client").execute();
//                }
//            };
//
//            h.postDelayed(r, 2000); // let to server to open the socket in advance

        }

    }

    //
    // Implementation of BLEUtil.IDeviceDiscoveredListener
    //
    @Override
    public void discovered(final BluetoothDevice device) {
        String deviceName = device.getName();
        String deviceAddress = device.getAddress();
        int state = device.getBondState(); // != BluetoothDevice.BOND_BONDED)

        checkAndConnect(device);
    }

    @TargetApi(18)
    private void checkAndConnect(BluetoothDevice device) {
        if( device.getType() == BluetoothDevice.DEVICE_TYPE_LE ) {
            device.connectGatt(this, true, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt,
                                                    int status,
                                                    int newState) {
                    super.onConnectionStateChange(gatt, status, newState);

                    String intentAction;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(LOG_TAG, "Connected to GATT server.");
                        Log.i(LOG_TAG, "Attempting to start service discovery:" +
                                gatt.discoverServices());
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(LOG_TAG, "Disconnected from GATT server.");
                    }
                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(LOG_TAG, "GATT_SUCCESS");
                    } else {
                        Log.d(LOG_TAG, "onServicesDiscovered received: " + status);
                    }
                }

                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(LOG_TAG, "GATT_SUCCESS");
                    }

                }
            });
        }
    }

}
