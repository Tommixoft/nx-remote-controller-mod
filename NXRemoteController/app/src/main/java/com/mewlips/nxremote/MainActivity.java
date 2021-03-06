package com.mewlips.nxremote;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.lukedeighton.wheelview.WheelView;
import com.lukedeighton.wheelview.adapter.WheelAdapter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String TAG = "MainActivity";
    private static final boolean DEBUG = true;

    private static final int VIDEO_STREAMER_PORT = 5678;
    private static final int XWIN_STREAMER_PORT = 5679;
    private static final int EXECUTOR_PORT = 5680;
    private static final int DISCOVERY_UDP_PORT = 5681;

    private static final int FRAME_WIDTH = 720;
    private static final int FRAME_HEIGHT = 480;
    private static final int FRAME_VIDEO_SIZE = FRAME_WIDTH * FRAME_HEIGHT * 3 / 2; // NV12
    private static final int XWIN_SEGMENT_NUM_PIXELS = 320;
    private static final int XWIN_SEGMENT_SIZE = 2 + (XWIN_SEGMENT_NUM_PIXELS * 4); // 2 bytes (INDEX) + 320 pixels (BGRA)
    private static final int DISCOVERY_PACKET_SIZE = 32;

    private static final int VIDEO_SIZE_FHD_HD = 0;
    private static final int VIDEO_SIZE_UHD = 1;
    private static final int VIDEO_SIZE_VGA = 2;

    private static final String XDOTOOL_COMMAND
            = "@chroot /opt/usr/apps/nx-remote-controller-mod/tools /usr/bin/xdotool";
    private static final String MOD_GUI_COMMAND
            = "@/opt/usr/nx-on-wake/mod_gui /opt/usr/nx-on-wake/main";
    private static final String GET_HEVC_STATE_COMMAND
            = "$cat /sys/kernel/debug/pmu/hevc/state";
    private static final String GET_MOV_SIZE_COMMAND_NX500
            = "$prefman get 0 0x0000a360 l";

    private ImageView mImageViewVideo;
    private ImageView mImageViewXWin;

    private ModeWheelAdapter mModeWheelAdapter;
    private WheelView mWheelViewMode;

    private JogWheelAdapter mJogWheelAdapterJog1;
    private WheelView mWheelViewJog1;

    private JogWheelAdapter mJogWheelAdapterJog2;
    private WheelView mWheelViewJog2;

    private ImageView mShutterButton;

    private Socket mVideoSocket;
    private InputStream mVideoReader;
    
    private Socket mXWinSocket;
    private InputStream mXWinReader;

    private Socket mExecutorSocket;
    private OutputStreamWriter mExecutorWriter;
    private DataInputStream mExecutorInputStream;
    private CommandExecutor mCommandExecutor;

    private DatagramSocket mDiscoverySocket;
    private String mCameraDaemonVersion;
    private String mCameraModel;
    private String mCameraIpAddress;

    private Button mButtonWifi;
    private Button mButtonHotSpot;
    private TextView mButtonEv;

    private boolean mOnRecord;

    private int mVideoSize = 0;

    private class VideoPlayer implements Runnable {
        private byte[] mBuffer = new byte[FRAME_VIDEO_SIZE];
        @Override
        public void run() {
            if (mCameraIpAddress == null) {
                return;
            }
            try {
                mVideoSocket = new Socket(mCameraIpAddress, VIDEO_STREAMER_PORT);
                mVideoReader = mVideoSocket.getInputStream();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            while (true) {
                int readSize = 0;
                try {
                    while (readSize != FRAME_VIDEO_SIZE) {
                        if (mVideoReader == null) {
                            readSize = -1;
                            break;
                        }
                        readSize += mVideoReader.read(mBuffer, readSize, FRAME_VIDEO_SIZE - readSize);
                        if (readSize == -1) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (readSize == -1) {
                    Log.d(TAG, "video read failed.");
                    try {
                        if (mVideoSocket != null) {
                            mVideoSocket.close();
                            mVideoSocket = null;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    break;
                }
                if (readSize == FRAME_VIDEO_SIZE) {
                    int width = FRAME_WIDTH;
                    int height = FRAME_HEIGHT;
                    if (mVideoSize == VIDEO_SIZE_VGA) {
                        width = 640;
                    } else if (mOnRecord && mVideoSize == VIDEO_SIZE_FHD_HD) {
                        height = 404;
                    } else if (mOnRecord && mVideoSize == VIDEO_SIZE_UHD) {
                        height = 380;
                    }
                    int[] intArray;
                    if (width == 640) {
                        intArray = convertYUV420_NV12toRGB8888(Arrays.copyOfRange(mBuffer, 0, width * height * 3 / 2), width, height);
                    } else {
                        intArray = convertYUV420_NV12toRGB8888(mBuffer, width, height);
                    }
                    final Bitmap bmp = Bitmap.createBitmap(intArray, width, height, Bitmap.Config.ARGB_8888);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mImageViewVideo.setImageBitmap(bmp);
                        }
                    });
                } else {
                    Log.d(TAG, "readSize = " + readSize);
                }
            }
        }
    };

    private class XWinViewer implements Runnable {
        private byte[] mBuffer = new byte[XWIN_SEGMENT_SIZE];
        final int[] mIntArray = new int[FRAME_WIDTH * FRAME_HEIGHT];

        @Override
        public void run() {
            if (mCameraIpAddress == null) {
                return;
            }
            try {
                mXWinSocket = new Socket(mCameraIpAddress, XWIN_STREAMER_PORT);
                mXWinReader = mXWinSocket.getInputStream();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            int updateCount = 0;
            while (true) {
                int readSize = 0;
                try {
                    while (readSize != XWIN_SEGMENT_SIZE) {
                        if (mXWinReader == null) {
                            readSize = -1;
                            break;
                        }
                        readSize += mXWinReader.read(mBuffer, readSize, XWIN_SEGMENT_SIZE - readSize);
                        if (readSize == -1) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (readSize == -1) {
                    Log.d(TAG, "xwin read failed.");
                    try {
                        if (mXWinSocket != null) {
                            mXWinSocket.close();
                            mXWinSocket = null;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    break;
                }
                if (readSize == XWIN_SEGMENT_SIZE) {
                    int index = (mBuffer[0] & 0xff) << 8 | mBuffer[1] & 0xff;
//                    Log.d(TAG, "index = " + index);
                    if (index == 0x0fff) { // end of frame
                        if (updateCount > 0) {
//                            Log.d(TAG, "update xwin");
                            final Bitmap bmp = Bitmap.createBitmap(mIntArray, FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mImageViewXWin.setImageBitmap(bmp);
                                }
                            });
                        }
                        updateCount = 0;
                    } else {
                        int offset = index * XWIN_SEGMENT_NUM_PIXELS;
                        for (int i = 0; i < XWIN_SEGMENT_NUM_PIXELS; i++) {
                            int j = 2 + i * 4;
                            int b = mBuffer[j] & 0xff;
                            int g = mBuffer[j+1] & 0xff;
                            int r = mBuffer[j+2] & 0xff;
                            int a = mBuffer[j+3] & 0xff;
                            mIntArray[offset + i] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                        updateCount++;
                    }
                } else {
                    Log.d(TAG, "readSize = " + readSize);
                }
            }
        }
    };

    private class CommandExecutor implements Runnable {
        private BlockingQueue<String> mBlockingQueue = new ArrayBlockingQueue<>(50);

        @Override
        public void run() {
            if (mCameraIpAddress == null) {
                return;
            }
            try {
                mExecutorSocket = new Socket(mCameraIpAddress, EXECUTOR_PORT);
                mExecutorWriter = new OutputStreamWriter(mExecutorSocket.getOutputStream());
                mExecutorInputStream = new DataInputStream(mExecutorSocket.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            setRemoteControlState(true);

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    execute(GET_HEVC_STATE_COMMAND);
                }
            }, 1000, 1000);

            while (true) {
                try {
                    String command = mBlockingQueue.take();
                    byte[] readBuf = new byte[1024];

                    if (!command.equals(GET_HEVC_STATE_COMMAND)) {
                        Log.d(TAG, "command = " + command);
                    }
                    try {
                        if (mExecutorWriter == null || mExecutorInputStream == null) {
                            if (mExecutorSocket != null) {
                                mExecutorSocket.close();
                                mExecutorSocket = null;
                                setRemoteControlState(false);
                                timer.cancel();
                            }
                            break;
                        }
                        mExecutorWriter.write(command + "\n");
                        mExecutorWriter.flush();

                        String commandOutput = "";
                        while (true) {
                            int size = mExecutorInputStream.readInt();
                            if (size > readBuf.length) {
                                Log.e(TAG, "size is too big. size = " + size);
                                break;
                            }
                            if (size > 0) {
                                while (size > 0) {
                                    int readSize = mExecutorInputStream.read(readBuf, 0, size);
                                    size -= readSize;
                                    commandOutput += new String(readBuf, 0, readSize);
                                }
                            } else {
                                if (command.equals(GET_HEVC_STATE_COMMAND)) {
                                    if (commandOutput.startsWith("off")) {
                                        if (mOnRecord) {
                                            Log.d(TAG, "record stopped.");
                                            mOnRecord = false;

                                            onRecordStopped();
                                        }
                                    } else if (commandOutput.startsWith("on")) {
                                        if (!mOnRecord) {
                                            Log.d(TAG, "on record.");
                                            mOnRecord = true;
                                            onRecordStarted();
                                        }
                                    }
                                } else if (command.equals(GET_MOV_SIZE_COMMAND_NX500)) {
                                    Log.d(TAG, "commandOutput = " + commandOutput);
                                    if (commandOutput.startsWith("[app] in memory:") &&
                                            commandOutput.length() > 30) {
                                        String output = commandOutput.substring(29);
                                        if (output.startsWith("0")) {
                                            Log.d(TAG, "4096x2160");
                                            mVideoSize = VIDEO_SIZE_UHD;
                                        } else if (output.startsWith("9") || output.startsWith("10") || output.startsWith("11")) {
                                            Log.d(TAG, "640x480");
                                            mVideoSize = VIDEO_SIZE_VGA;
                                        } else {
                                            Log.d(TAG, "16/9");
                                            mVideoSize = VIDEO_SIZE_FHD_HD;
                                        }
                                        setVideoMargin();
                                    }
                                } else {
                                    if (commandOutput.length() > 0) {
                                        Log.d(TAG, "command output (" + commandOutput.length() + ") = " + commandOutput);
                                    }
                                }
                                break;
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        try {
                            Log.d(TAG, "exectuor read or write failed.");
                            if (mExecutorSocket != null) {
                                mExecutorSocket.close();
                                mExecutorSocket = null;
                                setRemoteControlState(false);
                                timer.cancel();
                            }
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void execute(String command) {
            if (mExecutorSocket != null) {
                mBlockingQueue.add(command);
            }
        }
    }

    private void onRecordStarted() {
        runCommand("vfps=1");
        runCommand("xfps=1");
        runCommand(GET_MOV_SIZE_COMMAND_NX500); // NX500
    }

    private void onRecordStopped() {
        runCommand("vfps=5");
        runCommand("xfps=5");
        mVideoSize = -1;
        setVideoMargin();
    }

    private void setVideoMargin() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int width = mImageViewXWin.getWidth();
                int height = mImageViewXWin.getHeight();
                float wr = (float) width / 720f;
                float hr = (float) height / 480f;
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mImageViewVideo.getLayoutParams();
                switch (mVideoSize) {
                    case VIDEO_SIZE_FHD_HD:
                        layoutParams.setMargins(0, (int) (38 * hr), 0, (int) (38 * hr));
                        break;
                    case VIDEO_SIZE_UHD:
                        layoutParams.setMargins(0, (int) (50 * hr), 0, (int) (50 * hr));
                        break;
                    case VIDEO_SIZE_VGA:
                        layoutParams.setMargins((int) (40 * wr), 0, (int) (40 * wr), 0);
                        break;
                    default:
                        layoutParams.setMargins(0, 0, 0, 0);
                        break;
                }
                mImageViewVideo.setLayoutParams(layoutParams);
            }
        });
    }

    private void setRemoteControlState(final boolean onConnected) {
        Log.d(TAG, "setRemoteControlState(), onConnected = " + onConnected);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                RelativeLayout layout = (RelativeLayout) findViewById(R.id.layoutWifiInfo);
                if (onConnected) {
                    layout.setVisibility(View.GONE);
                } else {
                    layout.setVisibility(View.VISIBLE);
                }
            }
        });

    }

    private interface DiscoveryListener {
        void onFound(String version, String model, String ipAddress);
    }

    private class DiscoveryPacketReceiver implements Runnable {
        private DiscoveryListener mDiscoveryListener;

        public DiscoveryPacketReceiver(DiscoveryListener listener) {
            mDiscoveryListener = listener;
        }

        @Override
        public void run() {
            byte[] buf = new byte[DISCOVERY_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            try {
                while (true) {
                    if (mDiscoverySocket == null) {
                        mDiscoverySocket = new DatagramSocket(DISCOVERY_UDP_PORT, InetAddress.getByName("0.0.0.0"));
                        mDiscoverySocket.setBroadcast(true);
                    }
                    mDiscoverySocket.receive(packet);
                    byte[] data = packet.getData();
                    String discoveryMessage = new String(data);
                    String[] cameraInfos = discoveryMessage.split("\\|");
                    if (cameraInfos.length == 4) {
                        String header = cameraInfos[0];
                        String version = cameraInfos[1];
                        String model = cameraInfos[2];
                        // cameraInfos[3] is garbage
                        String ipAddress = packet.getAddress().getHostAddress();

                        if (header.equals("NX_REMOTE")) {
                            Log.d(TAG, "discovery packet received from " + ipAddress +
                                    ". [NX_REMOTE v" + version + " (" + model + ")]");
                            if (mDiscoveryListener != null) {
                                mDiscoveryListener.onFound(version , model, ipAddress);
                                Thread.sleep(5000); // wait for connect to camera
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, "DiscoveryPacketReceiver finished.");
        }
    };

    private void startDiscovery() {
        DiscoveryListener discoveryListener = new DiscoveryListener() {
            @Override
            public void onFound(String version, String model, String ipAddress) {
                disconnectFromCameraDaemon();
                mCameraDaemonVersion = version;
                mCameraIpAddress = ipAddress;
                mCameraModel = model;
                connectToCameraDaemon();
            }
        };
        new Thread(new DiscoveryPacketReceiver(discoveryListener)).start();
    }

    private void stopDiscovery() {
        if (mDiscoverySocket != null) {
            mDiscoverySocket.close();
            mDiscoverySocket = null;
        }
    }

    private void connectToCameraDaemon() {
        startVideoPlayer();
        startXWinViewer();
        startExecutor();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);
//        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);

//        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
//        fab.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                        .setAction("Action", null).show();
//            }
//        });

//        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
//        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
//                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
//        drawer.setDrawerListener(toggle);
//        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        View.OnTouchListener onTouchListener = new View.OnTouchListener() {
            private static final int SKIP_MOUSE_MOVE_COUNT = 10;
            private int mSkipCount;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction() & MotionEvent.ACTION_MASK;
                int x = (int) (event.getX() * (float)FRAME_WIDTH / (float)v.getWidth());
                int y = (int) (event.getY() * (float)FRAME_HEIGHT / (float)v.getHeight());
                String command = XDOTOOL_COMMAND + " ";

                Log.d(TAG, "action = " + action + ", x = " + x + ", y = " + y);

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        mSkipCount = SKIP_MOUSE_MOVE_COUNT;
                        runCommand(command + "mousemove " + x + " " + y + " mousedown 1");
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        mSkipCount--;
                        if (mSkipCount == 0) {
                            runCommand(command + "mousemove " + x + " " + y);
                            mSkipCount = SKIP_MOUSE_MOVE_COUNT;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        runCommand(command + "mousemove " + x + " " + y + " mouseup 1");
                        break;
                }
                return false;
            }
        };

        int[] intArray = new int[FRAME_WIDTH * FRAME_HEIGHT];
        Bitmap bmp = Bitmap.createBitmap(intArray, FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888);

        findViewById(R.id.layoutLcd).setOnTouchListener(onTouchListener);

        mImageViewVideo = (ImageView) findViewById(R.id.imageViewVideo);
        mImageViewVideo.setOnTouchListener(onTouchListener);
        mImageViewVideo.setImageBitmap(bmp);

        mImageViewXWin = (ImageView) findViewById(R.id.imageViewXWin);
        mImageViewXWin.setOnTouchListener(onTouchListener);
        mImageViewXWin.setImageBitmap(bmp);

        mWheelViewMode = (WheelView) findViewById(R.id.wheelViewMode);
        mModeWheelAdapter = new ModeWheelAdapter();
        mWheelViewMode.setAdapter(mModeWheelAdapter);

        mWheelViewMode.setOnWheelItemSelectedListener(new WheelView.OnWheelItemSelectListener() {
            @Override
            public void onWheelItemSelected(WheelView parent,  Drawable itemDrawable, int position) {
                //Log.d(TAG, "angle = " + mWheelViewMode.getAngleForPosition(position));
                mModeWheelAdapter.setSelectedPosition(position);
                Log.d(TAG, "position = " + position + ", " + mModeWheelAdapter.getModeOfSelectedPosition());
            }
        });
        mWheelViewMode.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction() & MotionEvent.ACTION_MASK;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mWheelViewMode.setSelected(mModeWheelAdapter.getSelectedPosition());
                    //mWheelViewMode.setAngle(mWheelViewMode.getAngleForPosition(mModeWheelAdapter.getSelectedPosition()));
                    Log.d(TAG, "onTouch, position = " + mModeWheelAdapter.getSelectedPosition() + ", " + mModeWheelAdapter.getModeOfSelectedPosition());
                    String keyCode = mModeWheelAdapter.getKeyCodeOfSelectedPosition();
                    keyClick(keyCode);
                }
                return false;
            }
        });

        mWheelViewJog1 = (WheelView) findViewById(R.id.wheelViewJog1);
        mJogWheelAdapterJog1 = new JogWheelAdapter();
        mWheelViewJog1.setAdapter(mJogWheelAdapterJog1);
        mWheelViewJog1.setOnWheelItemSelectedListener(new WheelView.OnWheelItemSelectListener() {
            private int mPrevPosition = 0;
            @Override
            public void onWheelItemSelected(WheelView parent,  Drawable itemDrawable, int position) {
                String key;
                if (mPrevPosition == 0 && position == mJogWheelAdapterJog1.getCount() - 1) {
                    key = KEY_JOG1_CW;
                } else if (mPrevPosition == mJogWheelAdapterJog1.getCount() -1 && position == 0) {
                    key = KEY_JOG1_CCW;
                } else if (mPrevPosition > position) {
                    key = KEY_JOG1_CW;
                } else {
                    key = KEY_JOG1_CCW;
                }
                keyClick(key);
                mPrevPosition = position;
            }
        });

        mWheelViewJog2 = (WheelView) findViewById(R.id.wheelViewJog2);
        mJogWheelAdapterJog2 = new JogWheelAdapter();
        mWheelViewJog2.setAdapter(mJogWheelAdapterJog2);
        mWheelViewJog2.setOnWheelItemSelectedListener(new WheelView.OnWheelItemSelectListener() {
            private int mPrevPosition = 0;
            @Override
            public void onWheelItemSelected(WheelView parent,  Drawable itemDrawable, int position) {
                String key;
                if (mPrevPosition == 0 && position == mJogWheelAdapterJog1.getCount() - 1) {
                    key = KEY_JOG_CW;
                } else if (mPrevPosition == mJogWheelAdapterJog1.getCount() - 1 && position == 0) {
                    key = KEY_JOG_CCW;
                } else if (mPrevPosition > position) {
                    key = KEY_JOG_CW;
                } else {
                    key = KEY_JOG_CCW;
                }
                keyClick(key);
                mPrevPosition = position;
            }
        });

        mShutterButton = (ImageView) findViewById(R.id.shutterButton);
        mShutterButton.setOnTouchListener(new View.OnTouchListener() {
            private static final int SKIP_MOVE_COUNT = 5;
            private int mSkipCount;

            private float mPrevY;
            private int mPrevHeight;
            private boolean mS1Downed;
            private boolean mS2Downed;
             @Override
             public boolean onTouch(View v, MotionEvent event) {
                 float currY;
                 int currHeight;
                 RelativeLayout.LayoutParams layoutParams;

                 int action = event.getAction() & MotionEvent.ACTION_MASK;
                 switch (action) {
                     case MotionEvent.ACTION_DOWN:
                         mPrevY = event.getY();
                         mPrevHeight = mShutterButton.getHeight();
                         keyDown(KEY_S1);
                         mS1Downed = true;
                         Log.d(TAG, "S1 down");
                         mSkipCount = SKIP_MOVE_COUNT;
                         break;
                     case MotionEvent.ACTION_MOVE:
                         currY = event.getY();
                         currHeight = mShutterButton.getHeight();
                         if (mPrevY < currY) {
                             if ((float)currHeight / (float)mPrevHeight > 0.9) {
                                 layoutParams = (RelativeLayout.LayoutParams) mShutterButton.getLayoutParams();
                                 layoutParams.setMargins(0, (int) (currY - mPrevY), 0, 0);
                                 mShutterButton.setLayoutParams(layoutParams);
                             }
                         }
                         if (mSkipCount == 0) {
                             if (currHeight < mPrevHeight) {
                                 if (!mS2Downed) {
                                     keyDown(KEY_S2);
                                     mS2Downed = true;
                                     Log.d(TAG, "S2 down");
                                 }
                             } else {
                                 if (mS2Downed) {
                                     keyUp(KEY_S2);
                                     mS2Downed = false;
                                     Log.d(TAG, "S2 up");
                                 }
                             }
                             mSkipCount = SKIP_MOVE_COUNT;
                         } else {
                             mSkipCount--;
                         }
                         break;
                     case MotionEvent.ACTION_UP:
                         layoutParams = (RelativeLayout.LayoutParams) mShutterButton.getLayoutParams();
                         layoutParams.setMargins(0, 0, 0, 0);
                         mShutterButton.setLayoutParams(layoutParams);
                         if (mS2Downed) {
                             keyUp(KEY_S2);
                             mS2Downed = false;
                             Log.d(TAG, "S2 up");
                         }
                         if (mS1Downed) {
                             keyUp(KEY_S1);
                             mS1Downed = false;
                             Log.d(TAG, "S1 up");
                         }
                         return false;
                     default:
                         return false;
                 }
                 return true;
             }
        });

        mButtonWifi = (Button) findViewById(R.id.buttonWifiSettings);
        mButtonWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });

        mButtonHotSpot = (Button) findViewById(R.id.buttonHotSpotSettings);
        mButtonHotSpot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Intent intent = new Intent(Intent.ACTION_MAIN, null);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                final ComponentName cn = new ComponentName("com.android.settings", "com.android.settings.TetherSettings");
                intent.setComponent(cn);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        });

        mButtonEv = (TextView) findViewById(R.id.keyEV);
        mButtonEv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getAction() & MotionEvent.ACTION_MASK;
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        runCommand(XDOTOOL_COMMAND + " keydown " + KEY_EV);
                        return true;
                    case MotionEvent.ACTION_UP:
                        runCommand(XDOTOOL_COMMAND + " keyup " + KEY_EV);
                        break;
                    default:
                        return true;
                }
                return false;
            }
        });

    }

    private void startVideoPlayer() {
        if (mVideoSocket == null) {
            new Thread(new VideoPlayer()).start();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImageViewVideo.setVisibility(View.VISIBLE);
            }
        });
    }

    private void startXWinViewer() {
        if (mXWinSocket == null) {
            new Thread(new XWinViewer()).start();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImageViewXWin.setVisibility(View.VISIBLE);
            }
        });
    }

    private void startExecutor() {
        if (mExecutorSocket == null) {
            mCommandExecutor = new CommandExecutor();
            new Thread(mCommandExecutor).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        startDiscovery();
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopDiscovery();
        disconnectFromCameraDaemon();
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

//    @Override
//    public boolean onCreateOptionsMenu(Menu menu) {
//        // Inflate the menu; this adds items to the action bar if it is present.
//        getMenuInflater().inflate(R.menu.main, menu);
//        return true;
//    }
//
//    @Override
//    public boolean onOptionsItemSelected(MenuItem item) {
//        // Handle action bar item clicks here. The action bar will
//        // automatically handle clicks on the Home/Up button, so long
//        // as you specify a parent activity in AndroidManifest.xml.
//        int id = item.getItemId();
//
//        //noinspection SimplifiableIfStatement
//        if (id == R.id.action_settings) {
//            return true;
//        }
//
//        return super.onOptionsItemSelected(item);
//    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_full_remote) {
            startVideoPlayer();
            startXWinViewer();
            startExecutor();
        } else if (id == R.id.nav_without_live_view) {
            closeVideoSocket();
            startXWinViewer();
            startExecutor();
        } else if (id == R.id.nav_live_view_only) {
            closeXWinSocket();
            startVideoPlayer();
            startExecutor();
        } else if (id == R.id.nav_buttons_only) {
            closeVideoSocket();
            closeXWinSocket();
            startExecutor();
            mImageViewVideo.setVisibility(View.INVISIBLE);
            mImageViewXWin.setVisibility(View.INVISIBLE);
        } else if (id == R.id.nav_lcd_on) {
            runCommand("@st app bb lcd on");
        } else if (id == R.id.nav_lcd_off) {
            runCommand("@st app bb lcd off");
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void closeVideoSocket() {
        if (mVideoReader != null) {
            try {
                mVideoReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mVideoReader = null;
        }
        if (mVideoSocket != null) {
            try {
                mVideoSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImageViewVideo.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void closeXWinSocket() {
        if (mXWinReader != null) {
            try {
                mXWinReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mXWinReader = null;
        }
        if (mXWinSocket != null) {
            try {
                mXWinSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mXWinSocket = null;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImageViewXWin.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void closeExecutorSocket() {
        if (mExecutorWriter != null) {
            try {
                mExecutorWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mExecutorWriter = null;
        }
        if (mExecutorInputStream != null) {
            try {
                mExecutorInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mExecutorInputStream = null;
        }
        if (mExecutorSocket != null) {
            try {
                mExecutorSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mExecutorSocket = null;
        }
        mCommandExecutor = null;
    }

    private void disconnectFromCameraDaemon() {
        closeVideoSocket();
        closeXWinSocket();
        closeExecutorSocket();

        mCameraDaemonVersion = null;
        mCameraIpAddress = null;
        mCameraModel = null;
    }

    /**
     * Converts YUV420 NV12 to RGB8888
     *
     * @param data byte array on YUV420 NV12 format.
     * @param width pixels width
     * @param height pixels height
     * @return a RGB8888 pixels int array. Where each int is a pixels ARGB.
     */
    public static int[] convertYUV420_NV12toRGB8888(byte [] data, int width, int height) {
        int size = width*height;
        int offset = size;
        int[] pixels = new int[size];
        int u, v, y1, y2, y3, y4;

        // i percorre os Y and the final pixels
        // k percorre os pixles U e V
        for(int i=0, k=0; i < size; i+=2, k+=2) {
            y1 = data[i  ]&0xff;
            y2 = data[i+1]&0xff;
            y3 = data[width+i  ]&0xff;
            y4 = data[width+i+1]&0xff;

            v = data[offset+k  ]&0xff;
            u = data[offset+k+1]&0xff;
            u = u-128;
            v = v-128;

            pixels[i  ] = convertYUVtoRGB(y1, u, v);
            pixels[i+1] = convertYUVtoRGB(y2, u, v);
            pixels[width+i  ] = convertYUVtoRGB(y3, u, v);
            pixels[width+i+1] = convertYUVtoRGB(y4, u, v);

            if (i!=0 && (i+2)%width==0)
                i+=width;
        }

        return pixels;
    }

    private static int convertYUVtoRGB(int y, int u, int v) {
        int r,g,b;

        r = y + (int)1.402f*v;
        g = y - (int)(0.344f*u +0.714f*v);
        b = y + (int)1.772f*u;
        r = r>255? 255 : r<0 ? 0 : r;
        g = g>255? 255 : g<0 ? 0 : g;
        b = b>255? 255 : b<0 ? 0 : b;
        return 0xff000000 | (b<<16) | (g<<8) | r;
    }

    private static final String KEY_UP = "KP_Up";
    private static final String KEY_LEFT = "KP_Left";
    private static final String KEY_RIGHT = "KP_Right";
    private static final String KEY_DOWN = "KP_Down";
    private static final String KEY_DEL = "KP_Delete";
    private static final String KEY_DEPTH = "Henkan_Mode";
    private static final String KEY_METER = "Hiragana_Katakana";
    private static final String KEY_OK = "KP_Enter";
    private static final String KEY_PWON = "XF86AudioRaiseVolume";
    private static final String KEY_PWOFF = "XF86PowerOff";
    private static final String KEY_RESET = "XF86PowerOff";
    private static final String KEY_S1 = "Super_L";
    private static final String KEY_S2 = "Super_R";
    private static final String KEY_MENU = "Menu";
    private static final String KEY_AEL = "XF86Favorites";
    private static final String KEY_REC = "XF86WebCam";
    private static final String KEY_FN = "XF86HomePage";
    private static final String KEY_EV = "XF86Reload";
    private static final String KEY_PB = "XF86Tools";
    private static final String KEY_AF_MODE = "Xf86TaskPane";
    private static final String KEY_WB = "XF86Launch6";
    private static final String KEY_ISO = "XF86Launch7";
    private static final String KEY_AF_ON = "XF86Launch9";
    private static final String KEY_LIGHT = "XF86TouchpadToggle";
    private static final String KEY_MF_ZOOM = "XF86TouchpadOff";
    private static final String KEY_WIFI = "XF86Mail";

    private static final String KEY_JOG1_CW = "XF86ScrollUp";
    private static final String KEY_JOG1_CCW = "XF86ScrollDown";
    private static final String KEY_JOG2_CW = "parenleft";
    private static final String KEY_JOG2_CCW = "parenright";
    private static final String KEY_JOG_CW = "XF86AudioNext";
    private static final String KEY_JOG_CCW = "XF86AudioPrev";

    private static final String KEY_MODE_SCENE = "XF86Send";
    private static final String KEY_MODE_SMART = "XF86Reply";
    private static final String KEY_MODE_P = "XF86MailForward";
    private static final String KEY_MODE_A = "XF86Save";
    private static final String KEY_MODE_S = "XF86Documents";
    private static final String KEY_MODE_M = "XF86Battery";
    private static final String KEY_MODE_CUSTOM1 = "XF86WLAN";
    private static final String KEY_MODE_CUSTOM2 = "XF86Bluetooth";
    //private static final String KEY_MODE_SAS = "";

    private static final String KEY_WHEEL_CW = "Left";
    private static final String KEY_WHEEL_CCW = "Right";

    private static final String KEY_DRIVE_SINGLE = "XF86Search";
    private static final String KEY_DRIVE_CONTI_N = "XF86Go";
    private static final String KEY_DRIVE_CONTI_H = "XF86Finance";
    private static final String KEY_DRIVE_TIMER = "XF86Game";
    private static final String KEY_DRIVE_BRACKET = "XF86Shop";

    private void runCommand(String command) {
        if (mCommandExecutor != null) {
            mCommandExecutor.execute(command);
        }
    }

    public void onButtonClick(View v) {
        String key = "";
        switch (v.getId()) {
            case R.id.keyAEL:
                key = KEY_AEL;
                break;
            case R.id.keyEV:
                key = KEY_EV;
                break;
            case R.id.keyRec:
                key = KEY_REC;
                break;
            case R.id.keyMod:
                runCommand(MOD_GUI_COMMAND);
                break;
            case R.id.keyMenu:
                key = KEY_MENU;
                break;
            case R.id.keyUp:
                key = KEY_UP;
                break;
            case R.id.keyFn:
                key = KEY_FN;
                break;
            case R.id.keyLeft:
                key = KEY_LEFT;
                break;
            case R.id.keyOK:
                key = KEY_OK;
                break;
            case R.id.keyRight:
                key = KEY_RIGHT;
                break;
            case R.id.keyPB:
                key = KEY_PB;
                break;
            case R.id.keyDown:
                key = KEY_DOWN;
                break;
            case R.id.keyDel:
                key = KEY_DEL;
                break;
        }
        if (!key.equals("")) {
            keyClick(key);
        }
    }

    private void keyDown(String key) {
        runCommand(XDOTOOL_COMMAND + " keydown " + key);
    }

    private void keyUp(String key) {
        runCommand(XDOTOOL_COMMAND + " keyup " + key);
    }

    private void keyClick(String key) {
        runCommand(XDOTOOL_COMMAND + " key " + key);
    }

    private class TextDrawable extends Drawable {
        private static final int DEFAULT_COLOR = Color.WHITE;
        private static final int DEFAULT_TEXTSIZE = 15;
        private Paint mPaint;
        private CharSequence mText;
        private int mIntrinsicWidth;
        private int mIntrinsicHeight;

        public TextDrawable(Resources res, CharSequence text) {
            mText = text;
            mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaint.setColor(DEFAULT_COLOR);
            mPaint.setTextAlign(Paint.Align.CENTER);
            float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    DEFAULT_TEXTSIZE, res.getDisplayMetrics());
            mPaint.setTextSize(textSize);
            mIntrinsicWidth = (int) (mPaint.measureText(mText, 0, mText.length()) + .5);
            mIntrinsicHeight = mPaint.getFontMetricsInt(null);
        }
        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawText(mText, 0, mText.length(),
                    bounds.centerX(), bounds.centerY(), mPaint);
        }
        @Override
        public int getOpacity() {
            return mPaint.getAlpha();
        }
        @Override
        public int getIntrinsicWidth() {
            return mIntrinsicWidth;
        }
        @Override
        public int getIntrinsicHeight() {
            return mIntrinsicHeight;
        }
        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }
        @Override
        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }
    }

    private class ModeWheelAdapter implements WheelAdapter {
        private class Mode {
            public String mMode;
            public TextDrawable mDrawable;
            public String mKey;
            public Mode(String mode, String key) {
                mMode = mode;
                mDrawable = new TextDrawable(res, mode);
                mKey = key;
            }
            public String getMode() {
                return mMode;
            }
            public Drawable getDrawable() {
                return mDrawable;
            }
            public String getKey() {
                return mKey;
            }
        }

        private Resources res = getResources();
        Mode[] mModes = {
                new Mode("C1", KEY_MODE_CUSTOM1),
                new Mode("M", KEY_MODE_M),
                new Mode("S", KEY_MODE_S),
                new Mode("A", KEY_MODE_A),
                new Mode("P", KEY_MODE_P),
                new Mode("AUTO", KEY_MODE_SMART),
                new Mode("SCN", KEY_MODE_SCENE),
                new Mode("C2", KEY_MODE_CUSTOM2),
                //new Mode("SAS", KEY_MODE_SAS), // FIXME: find KEY_MODE_SAS
        };
        private int mSelectedPosition;

        @Override
        public Drawable getDrawable(int position) {
            return mModes[position].getDrawable();
        }

        @Override
        public int getCount() {
            return mModes.length;
        }

        public void setSelectedPosition(int position) {
            mSelectedPosition = position;
        }

        public int getSelectedPosition() {
            return mSelectedPosition;
        }

        public String getKeyCodeOfSelectedPosition() {
            return mModes[mSelectedPosition].getKey();
        }
        public String getModeOfSelectedPosition() {
            return mModes[mSelectedPosition].getMode();
        }
    }

    private class JogWheelAdapter implements WheelAdapter {
        private Drawable mDrawable = new TextDrawable(getResources(), "I");

        @Override
        public Drawable getDrawable(int position) {
            return mDrawable;
        }

        @Override
        public int getCount() {
            return 50;
        }
    }

}
