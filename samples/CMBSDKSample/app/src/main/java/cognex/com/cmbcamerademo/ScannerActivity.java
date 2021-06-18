package cognex.com.cmbcamerademo;

import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import com.cognex.dataman.sdk.CameraMode;
import com.cognex.dataman.sdk.ConnectionState;
import com.cognex.dataman.sdk.DataManDeviceClass;
import com.cognex.dataman.sdk.DataManSystem;
import com.cognex.dataman.sdk.DmccResponse;
import com.cognex.dataman.sdk.PreviewOption;
import com.cognex.dataman.sdk.exceptions.CameraPermissionException;
import com.cognex.mobile.barcode.sdk.ReadResult;
import com.cognex.mobile.barcode.sdk.ReadResults;
import com.cognex.mobile.barcode.sdk.ReaderDevice;
import com.cognex.mobile.barcode.sdk.ReaderDevice.Availability;
import com.cognex.mobile.barcode.sdk.ReaderDevice.OnConnectionCompletedListener;
import com.cognex.mobile.barcode.sdk.ReaderDevice.ReaderDeviceListener;
import com.cognex.mobile.barcode.sdk.Symbology;

import java.util.ArrayList;
import java.util.HashMap;

/***********************************************************************************
 *  File name   : ScannerActivity.java
 *  Description : Activity for cmbSDK sample application
 *  Comments    :
 *
 *    This sample application has been designed to show how simple it is to write
 *    a single application with the cmbSDK that will work with any its supported
 *    devices: an MX-1xxx mobile terminal, or just
 *    the built-in camera of a phone or tablet.
 *
 *    It implements a single view with a pick list for choosing which type of
 *    device to connection to. In our example, we are using the lifecycle of
 *    the Activity to connect and disconnect to the device (since this is a
 *    single view application); however, this is not the only way to use the SDK;
 *    in a multiple view application you may not want to tie connecting and
 *    disconnecting to a Activity, but to a helper class or otherwise.
 *
 *  Copyright(C) : 2017-present, Cognex Corporation. All rights reserved.
 ***********************************************************************************/

public class ScannerActivity extends AppCompatActivity implements
        OnConnectionCompletedListener, ReaderDeviceListener,
        ActivityCompat.OnRequestPermissionsResultCallback {

    static final int REQUEST_PERMISSION_CODE = 12322;
    ReaderDevice readerDevice;
    boolean isScanning = false;
    boolean availabilityListenerStarted = false;

    // UI Views
    ListView listViewResult;
    TextView tvConnectionStatus;
    Button btnScan;

    //----------------------------------------------------------------------------
    // The cmbSDK supports multi-code scanning (scanning multiple barcodes at
    // one time); thus scan results are returned as an array. Note that
    // this sample app does not demonstrate the use of these multi-code features.
    //----------------------------------------------------------------------------
    ArrayList<HashMap<String, String>> scanResults;
    SimpleAdapter resultListAdapter;

    //----------------------------------------------------------------------------
    // If usePreConfiguredDeviceType is YES, then the app will create a reader
    // using the values of deviceClass/cameraMode. Otherwise, the app presents
    // a pick list for the user to select either MX-1xxx, or the built-in
    // camera.
    //----------------------------------------------------------------------------
    private static final boolean USE_PRECONFIGURED_DEVICE = false;
    DataManDeviceClass param_deviceClass = DataManDeviceClass.MX;
    int param_cameraMode = CameraMode.NO_AIMER;

    //region Activity Lifecycle methods

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        listViewResult = (ListView) findViewById(R.id.listResult);

        tvConnectionStatus = (TextView) findViewById(R.id.tvStatus);

        btnScan = (Button) findViewById(R.id.btnScan);
        btnScan.setEnabled(false);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (readerDevice != null) {
                    toggleScanner();
                }
            }
        });

        scanResults = new ArrayList<HashMap<String, String>>();
        resultListAdapter = new SimpleAdapter(this, scanResults, android.R.layout.simple_list_item_2, new String[]{"resultText", "resultType"}, new int[]{android.R.id.text1, android.R.id.text2}) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);

                ((TextView) view.findViewById(android.R.id.text1)).setTextSize(18);
                ((TextView) view.findViewById(android.R.id.text1)).setTextColor(Color.WHITE);
                ((TextView) view.findViewById(android.R.id.text2)).setTextColor(Color.LTGRAY);

                return view;
            }
        };

        listViewResult.setAdapter(resultListAdapter);

        // Get cmbSDK version number
        ((TextView) findViewById(R.id.tvVersion)).setText(DataManSystem.getVersion());

        // initialize and connect to MX/Phone Camera here
        if (USE_PRECONFIGURED_DEVICE) {
            createReaderDevice();
        } else {
            selectDeviceFromPicker();
        }
    }

    //----------------------------------------------------------------------------
    // When an applicaiton is suspended, the connection to the scanning device needs
    // to be closed in onStop; thus when we are resumed (onStart) we
    // have to restore the connection (assuming we had one). This is the method
    // we will use to do this.
    //----------------------------------------------------------------------------
    @Override
    protected void onStart() {
        super.onStart();

        if (readerDevice != null
                && readerDevice.getAvailability() == Availability.AVAILABLE
                && readerDevice.getConnectionState() != ConnectionState.Connecting && readerDevice.getConnectionState() != ConnectionState.Connected) {

            //Listen when a MX device has became available/unavailable
            if (readerDevice.getDeviceClass() == DataManDeviceClass.MX && !availabilityListenerStarted) {
                readerDevice.startAvailabilityListening();
                availabilityListenerStarted = true;
            }

            connectToReaderDevice();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // As the activity is closed, stop any active scanning
        if (readerDevice != null &&
                readerDevice.getConnectionState() == ConnectionState.Connected) {
            readerDevice.stopScanning();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // stop listening to device availability to avoid resource leaks
        if (availabilityListenerStarted) {
            readerDevice.stopAvailabilityListening();
            availabilityListenerStarted = false;
        }

        // If we have connection to a reader, disconnect
        if (readerDevice != null) {
            readerDevice.disconnect();
        }
    }

    //endregion

    //region Activity Options Menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.scanner_activity, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        }

        if (id == R.id.action_device) {
            selectDeviceFromPicker();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    //endregion

    // region Update UI

    // Update the UI of the app (scan button, connection state label) depending on the current self.readerDevice connection state
    private void updateUIByConnectionState() {
        if (readerDevice != null && readerDevice.getConnectionState() == ConnectionState.Connected) {
            tvConnectionStatus.setText("Connected");
            tvConnectionStatus.setBackgroundResource(R.drawable.connection_status_bg);

            btnScan.setEnabled(true);
        } else {
            tvConnectionStatus.setText("Disconnected");
            tvConnectionStatus.setBackgroundResource(R.drawable.connection_status_bg_disconnected);

            btnScan.setEnabled(false);
        }

        btnScan.setText(btnScan.isEnabled() ? "START SCANNING" : "(NOT CONNECTED)");
    }

    private void clearResult() {
        scanResults.clear();
        resultListAdapter.notifyDataSetChanged();
    }

    //endregion

    //region Select Device

    //----------------------------------------------------------------------------
    // This is the pick list for choosing the type of reader connection
    //----------------------------------------------------------------------------
    private void selectDeviceFromPicker() {
        AlertDialog.Builder devicePickerBuilder = new AlertDialog.Builder(this);
        devicePickerBuilder.setTitle("Select device");

        final ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);

        //
        // Option for scanning with an MX-1000 or MX-1502
        //
        arrayAdapter.add("MX Scanner (MX-1xxx)");

        //
        // Option for scanning with the phone/tablet's builtin camera
        //
        arrayAdapter.add("Phone Camera");

        devicePickerBuilder.setAdapter(arrayAdapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    default:
                    case 0:
                        param_deviceClass = DataManDeviceClass.MX;
                        break;
                    case 1:
                        param_deviceClass = DataManDeviceClass.PhoneCamera;
                        break;
                }
            }
        });

        devicePickerBuilder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                createReaderDevice();
            }
        });

        devicePickerBuilder.setNegativeButton("Cancel", null);
        devicePickerBuilder.create().show();
    }

    //endregion

    // Create a readerDevice using the selected option from "selectDeviceFromPicker"
    // Optionally, if you don't want to use multiple device types, you can remove the switch statement and keep only the one type that you need
    private void createReaderDevice() {
        if (availabilityListenerStarted) {
            readerDevice.stopAvailabilityListening();

            availabilityListenerStarted = false;
        }

        if (readerDevice != null) {
            readerDevice.disconnect();

            readerDevice = null;
        }

        switch (param_deviceClass) {
            //***************************************************************************************
            // Create an MX-1xxx reader  (note that no license key in needed)
            //***************************************************************************************
            default:
            case MX:
                readerDevice = ReaderDevice.getMXDevice(this);

                //Listen when a MX device has became available/unavailable
                if (!availabilityListenerStarted) {
                    readerDevice.startAvailabilityListening();
                    availabilityListenerStarted = true;
                }
                break;

            //***************************************************************************************
            // Create a camera reader
            //
            // NOTE:  if we're scanning using the built-in camera
            //       of the mobile phone or tablet, then the SDK requires a license key. Refer to
            //       the SDK's documentation on obtaining a license key as well as the methods for
            //       passing the key to the SDK (in this example, we're relying on an entry in
            //       AndroidManifest--there are also getPhoneCameraDevice methods where it can be passed
            //       as a parameter).
            //***************************************************************************************
            case PhoneCamera:
                readerDevice = ReaderDevice.getPhoneCameraDevice(this,
                        param_cameraMode, PreviewOption.DEFAULTS);

                break;
        }

        // set listeners and connect to device
        readerDevice.setReaderDeviceListener(this);
        connectToReaderDevice();
        updateUIByConnectionState();
    }

    //region ReaderDevice CONNECT - DISCONNECT

    // Before the self.readerDevice can be configured or used, a connection needs to be established
    private void connectToReaderDevice() {
        readerDevice.connect(ScannerActivity.this);
    }

    //endregion

    //----------------------------------------------------------------------------
    // This is an example of configuring the device. In this sample application, we
    // configure the device every time the connection state changes to connected (see
    // the onConnectionStateChanged callback below), as this is the best
    // way to garentee it is setup the way we want it. Not only does this garentee
    // that the device is configured when we initially connect, but also covers the
    // case where an MX reader has hibernated (and we're reconnecting)--unless
    // setting changes are explicitly saved to non-volatile memory, they can be lost
    // when the MX hibernates or reboots.
    //
    // These are just example settings; in your own application you will want to
    // consider which setting changes are optimal for your application. It is
    // important to note that the different supported devices have different, out
    // of the box defaults:
    //
    //    * MX-1xxx Mobile Terminals have the following symbologies enabled by default:
    //        - Data Matrix
    //        - UPC/EAN
    //        - Code 39
    //        - Code 93
    //        - Code 128
    //        - Interleaved 2 of 5
    //        - Codabar
    //
    //    * camera reader has NO symbologies enabled by default
    //
    // For the best scanning performance, it is recommended to only have the barcode
    // symbologies enabled that your application actually needs to scan. If scanning
    // with an MX-1xxx, that may mean disabling some of the defaults (or enabling
    // symbologies that are off by default).
    //
    // Keep in mind that this sample application works with both devices (MX-1xxx and built-in camera),
    // so in our example below we show explicitly enabling symbologies as well as
    // explicitly disabling symbologies (even if those symbologies may already be on/off
    // for the device being used).
    //
    // We also show how to send configuration commands that may be device type
    // specific--again, primarily for demonstration purposes.
    //----------------------------------------------------------------------------
    private void configureReaderDevice() {
        //----------------------------------------------
        // Explicitly enable the symbologies we need
        //----------------------------------------------
        readerDevice.setSymbologyEnabled(Symbology.C11, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.DATAMATRIX");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.C128, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.DATAMATRIX");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.DATAMATRIX, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.DATAMATRIX");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.AZTECCODE, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.DATAMATRIX");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.MAXICODE, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.DATAMATRIX");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.QR, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.DATAMATRIX");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.C128, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.C128");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.UPC_EAN, true, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to enable Symbology.UPC_EAN");
            }
        });

        //-------------------------------------------------------
        // Explicitly disable symbologies we know we don't need
        //-------------------------------------------------------
        readerDevice.setSymbologyEnabled(Symbology.CODABAR, false, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to disable Symbology.CODABAR");
            }
        });
        readerDevice.setSymbologyEnabled(Symbology.C93, false, new ReaderDevice.OnSymbologyListener() {
            @Override
            public void onSymbologyEnabled(ReaderDevice reader, Symbology symbology, Boolean enabled, Throwable error) {
                if (error != null)
                    Log.e(this.getClass().getSimpleName(), "Failed to disable Symbology.C93");
            }
        });

        //---------------------------------------------------------------------------
        // Below are examples of sending DMCC commands and getting the response
        //---------------------------------------------------------------------------
        readerDevice.getDataManSystem().sendCommand("GET DEVICE.TYPE", new DataManSystem.OnResponseReceivedListener() {
            @Override
            public void onResponseReceived(DataManSystem dataManSystem, DmccResponse response) {
                if (response.getError() == null) {
                    Log.d("Device type", response.getPayLoad());
                }
            }
        });

        readerDevice.getDataManSystem().sendCommand("GET DEVICE.FIRMWARE-VER", new DataManSystem.OnResponseReceivedListener() {
            @Override
            public void onResponseReceived(DataManSystem dataManSystem, DmccResponse response) {
                if (response.getError() == null) {
                    Log.d("Firmware version", response.getPayLoad());
                }
            }
        });

        //---------------------------------------------------------------------------
        // We are going to explicitly turn off image results (although this is the
        // default). The reason is that enabling image results with an MX-1xxx
        // scanner is not recommended unless your application needs the scanned
        // image--otherwise scanning performance can be impacted.
        //---------------------------------------------------------------------------
        readerDevice.enableImage(false);
        readerDevice.enableImageGraphics(false);

        //---------------------------------------------------------------------------
        // Device specific configuration examples
        //---------------------------------------------------------------------------

        if (readerDevice.getDeviceClass() == DataManDeviceClass.PhoneCamera) {
            //---------------------------------------------------------------------------
            // Phone/tablet
            //---------------------------------------------------------------------------

            // Set the SDK's decoding effort to level 3
            readerDevice.getDataManSystem().sendCommand("SET DECODER.EFFORT 3");
        } else if (readerDevice.getDeviceClass() == DataManDeviceClass.MX) {
            //---------------------------------------------------------------------------
            // MX-1xxx
            //---------------------------------------------------------------------------

            //---------------------------------------------------------------------------
            // Save our configuration to non-volatile memory (on an MX-1xxx; for the
            // phone, this has no effect). However, if the MX hibernates or is
            // rebooted, our settings will be retained.
            //---------------------------------------------------------------------------
            readerDevice.getDataManSystem().sendCommand("CONFIG.SAVE");
        }
    }

    private void toggleScanner() {
        if (isScanning) {
            readerDevice.stopScanning();
            btnScan.setText("START SCANNING");
        } else {
            readerDevice.startScanning();
            btnScan.setText("STOP SCANNING");
        }

        isScanning = !isScanning;
    }

    //region ReaderDevice listener implementations

    // This is called when a MX-1xxx device has became available (USB cable was plugged, or MX device was turned on),
    // or when a MX-1xxx that was previously available has become unavailable (USB cable was unplugged, turned off due to inactivity or battery drained)
    @Override
    public void onAvailabilityChanged(ReaderDevice reader) {
        if (reader.getAvailability() == Availability.AVAILABLE) {
            connectToReaderDevice();
        } else if (reader.getAvailability() == Availability.UNAVAILABLE) {
            AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert
                    .setTitle("Device became unavailable")
                    .setPositiveButton("OK", null)
                    .create()
                    .show();
        }
    }

    // The connect method has completed, here you can see whether there was an error with establishing the connection or not
    @Override
    public void onConnectionCompleted(ReaderDevice readerDevice, Throwable error) {
        // If we have valid connection error param will be null,
        // otherwise here is error that inform us about issue that we have while connecting to reader device
        if (error != null) {

            // ask for Camera Permission if necessary
            if (error instanceof CameraPermissionException)
                ActivityCompat.requestPermissions(((ScannerActivity) this), new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION_CODE);

            updateUIByConnectionState();
        }
    }

    // This is called when a connection with the self.readerDevice has been changed.
    // The readerDevice is usable only in the "ConnectionState.Connected" state
    @Override
    public void onConnectionStateChanged(ReaderDevice reader) {
        clearResult();
        if (reader.getConnectionState() == ConnectionState.Connected) {
            // We just connected, so now configure the device how we want it
            configureReaderDevice();
        }

        isScanning = false;
        updateUIByConnectionState();
    }

    // This is called after scanning has completed, either by detecting a barcode, canceling the scan by using the on-screen button or a hardware trigger button, or if the scanning timed-out
    @Override
    public void onReadResultReceived(ReaderDevice readerDevice, ReadResults results) {
        clearResult();

        if (results.getSubResults() != null && results.getSubResults().size() > 0) {
            for (ReadResult subResult : results.getSubResults()) {
                createResultItem(subResult);
            }
        } else if (results.getCount() > 0) {
            createResultItem(results.getResultAt(0));
        }

        isScanning = false;
        btnScan.setText("START SCANNING");
        resultListAdapter.notifyDataSetChanged();
    }

    //endregion

    private void createResultItem(ReadResult result) {
        HashMap<String, String> item = new HashMap<String, String>();
        if (result.isGoodRead()) {
            item.put("resultText", result.getReadString());

            Symbology sym = result.getSymbology();
            if (sym != null)
                item.put("resultType", result.getSymbology().getName());
            else
                item.put("resultType", "UNKNOWN SYMBOLOGY");
        } else {
            item.put("resultText", "NO READ");
            item.put("resultType", "");
        }

        scanResults.add(item);
    }

    //region Handle permission for the phone camera
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // Check result from permission request. If it is allowed by the user, connect to readerDevice
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (readerDevice != null && readerDevice.getConnectionState() != ConnectionState.Connected)
                    readerDevice.connect(ScannerActivity.this);
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(((ScannerActivity) this), Manifest.permission.CAMERA)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this)
                            .setMessage("You need to allow access to the Camera")
                            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(
                                        DialogInterface dialogInterface,
                                        int i) {
                                    ActivityCompat.requestPermissions(ScannerActivity.this, new String[]{Manifest.permission.CAMERA},
                                            REQUEST_PERMISSION_CODE);
                                }
                            })
                            .setNegativeButton("Cancel", null);
                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
            }
        }
    }

    //endregion
}
