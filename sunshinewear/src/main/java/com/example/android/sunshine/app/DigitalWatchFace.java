/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class DigitalWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<DigitalWatchFace.Engine> mWeakReference;

        public EngineHandler(DigitalWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            DigitalWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mAmbient;

        // date and format objects
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;
        SimpleDateFormat mTimeFormat;

        // paint for drawing different elements
        Paint mBackgroundPaint;
        Paint mWhiteTextPaint;
        Paint mGreyTextPaint;
        Paint mWeatherIconPaint;

        GoogleApiClient mGoogleApiClient;

        // tags to get weather info from data API
        String LOW_TEMP = "LOW_TEMP";
        String HIGH_TEMP = "HIGH_TEMP";
        String WEATHER_ICON = "WEATHER_ICON";
        String DATA_PATH = "/sunshine";

        String mHighTemp;
        String mLowTemp;
        Bitmap mWeatherIconBitmap;

        // position to draw elements
        float mTimeXOffset;
        float mTimeYOffset;
        float mDateXOffset;
        float mDateYOffset;
        float mSeparatorXOffset;
        float mSeparatorYOffset;
        float mIconXOffset;
        float mIconYOffset;
        float mHighXOffset;
        float mHighYOffset;
        float mLowXOffset;
        float mLowYOffset;
        float mSeparatorLength;

        // size of elements
        float mTimeTextSize;
        float mDateTextSize;
        float mHighTempTextSize;
        float mLowTempTextSize;
        int iconSize;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Time zone change - redraw watch face
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(DigitalWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.CENTER)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .build());
            Resources resources = DigitalWatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);

            // initialize paint objects
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background_sunny));

            mWhiteTextPaint = new Paint();
            mWhiteTextPaint = createTextPaint(resources.getColor(R.color.primary_text));

            mGreyTextPaint = new Paint();
            mGreyTextPaint = createTextPaint(resources.getColor(R.color.secondary_text));

            mWeatherIconPaint = new Paint();

            mCalendar = Calendar.getInstance();
            initFormats();

            // build data access client
            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(Engine.this)
                    .addOnConnectionFailedListener(Engine.this)
                    .build();
        }

        /**
         * When the watch face is used initially, access the data layer on the connected device and
         * retrieve weather information
         */
        void getInitialWeatherData () {
            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                @Override
                public void onResult(NodeApi.GetConnectedNodesResult nodes) {
                    Log.i("WATCH", "getConnectedNodes result");
                    Node connectedNode = null;
                    for (Node node : nodes.getNodes()) {
                        connectedNode = node;
                    }
                    if (connectedNode == null) {
                        return;
                    }

                    Uri uri = new Uri.Builder()
                            .scheme(PutDataRequest.WEAR_URI_SCHEME)
                            .path(DATA_PATH)
                            .authority(connectedNode.getId())
                            .build();

                    Wearable.DataApi.getDataItem(mGoogleApiClient, uri)
                            .setResultCallback(
                                    new ResultCallback<DataApi.DataItemResult>() {
                                        @Override
                                        public void onResult(DataApi.DataItemResult dataItemResult) {
                                            Log.i("WATCH", "getDataItem result");
                                            if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
                                                Log.i("WATCH", "Received data item result from connected node");
                                                DataMap dataMap = DataMapItem.fromDataItem(dataItemResult.getDataItem()).getDataMap();
                                                extractWeatherData(dataMap);
                                            }
                                        }
                                    });
                }
            });
        }

        /**
         * Get weather info from the dataMap object provided
         * @param dataMap
         */
        void extractWeatherData (DataMap dataMap) {
            mHighTemp = dataMap.getString(HIGH_TEMP);
            mLowTemp = dataMap.getString(LOW_TEMP);
            Log.i("WATCH","High temp: "+mHighTemp);
            Log.i("WATCH", "Low temp: " + mLowTemp);

            Asset weatherIconAsset = dataMap.getAsset(WEATHER_ICON);
            loadBitmapFromAsset(weatherIconAsset);
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.i("WATCH","onConnected");
            // get initial weather info from connected device
            getInitialWeatherData();

            // listen to data change event
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i("WATCH", "onConnectionSuspended");
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.w("WATCH", "onConnectionFailed");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.i("WATCH", "onDataChanged");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(DATA_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        extractWeatherData(dataMap);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);

            // stop listen to the data change event
            Wearable.DataApi.removeListener(mGoogleApiClient, Engine.this);

            // disconnect from data layer
            if(mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            }
            super.onDestroy();
        }

        /**
         * Create a text paint with specified colour
         * @param textColor
         * @return
         */
        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Load bit map from asset provided
         * @param asset
         */
        public void loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                Log.e("WATCH", "Asset received on watch face is null");
                return;
            }
            // convert asset into a file descriptor and get notified when it's ready
            Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                @Override
                public void onResult(DataApi.GetFdForAssetResult getFdForAssetResult) {
                    InputStream assetInputStream = getFdForAssetResult.getInputStream();
                    if (assetInputStream == null) {
                        Log.w("WATCH", "Requested an unknown Asset.");
                        return;
                    }
                    mWeatherIconBitmap = BitmapFactory.decodeStream(assetInputStream);
                }
            });
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                Wearable.DataApi.removeListener(mGoogleApiClient, Engine.this);
                if(mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            DigitalWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            DigitalWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = DigitalWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mTimeXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_time_x_offset);

            // TODO: 26/01/2016 move to onCreate
            mDateXOffset = resources.getDimension(R.dimen.digital_date_x_offset);
            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mSeparatorXOffset = resources.getDimension(R.dimen.digital_separator_x_offset);
            mSeparatorYOffset = resources.getDimension(R.dimen.digital_separator_y_offset);
            mIconXOffset = resources.getDimension(R.dimen.digital_icon_x_offset);
            mIconYOffset = resources.getDimension(R.dimen.digital_icon_y_offset);
            mHighXOffset = resources.getDimension(R.dimen.digital_high_x_offset);
            mHighYOffset = resources.getDimension(R.dimen.digital_high_y_offset);
            mLowXOffset = resources.getDimension(R.dimen.digital_low_x_offset);
            mLowYOffset = resources.getDimension(R.dimen.digital_low_y_offset);

            mSeparatorLength = resources.getDimension(R.dimen.digital_separator_length);

            iconSize = resources.getInteger(R.integer.icon_size);

            mTimeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mDateTextSize = resources.getDimension(R.dimen.digital_date_text_size);
            mHighTempTextSize = resources.getDimension(R.dimen.digital_high_temp_size);
            mLowTempTextSize = resources.getDimension(R.dimen.digital_low_temp_text_size);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mWhiteTextPaint.setAntiAlias(!inAmbientMode);
                    mGreyTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = DigitalWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw time
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            String timeText = mTimeFormat.format(mDate);

            mWhiteTextPaint.setTextSize(mTimeTextSize);
            canvas.drawText(timeText, mTimeXOffset, mTimeYOffset, mWhiteTextPaint);

            if(!mAmbient) {
                // Draw date
                String dateText = mDateFormat.format(mDate);
                mGreyTextPaint.setTextSize(mDateTextSize);
                canvas.drawText(dateText, mDateXOffset, mDateYOffset, mGreyTextPaint);

                // Draw weather information if available
                if(mHighTemp != null && mLowTemp != null) {
                    // Draw high
                    mWhiteTextPaint.setTextSize(mHighTempTextSize);
                    canvas.drawText(mHighTemp, mHighXOffset, mHighYOffset, mWhiteTextPaint);
                    // Draw low
                    mGreyTextPaint.setTextSize(mLowTempTextSize);
                    canvas.drawText(mLowTemp, mLowXOffset, mLowYOffset, mGreyTextPaint);
                    // Draw separator
                    mGreyTextPaint.setStrokeWidth(0);
                    canvas.drawLine(mSeparatorXOffset, mSeparatorYOffset, mSeparatorXOffset + mSeparatorLength, mSeparatorYOffset, mGreyTextPaint);
                }
                if(mWeatherIconBitmap != null) {
                    // Scale and draw icon
                    float ratio = iconSize / (float) mWeatherIconBitmap.getWidth();
                    float middleX = iconSize / 2.0f;
                    float middleY = iconSize / 2.0f;

                    Matrix scaleMatrix = new Matrix();
                    scaleMatrix.setScale(ratio, ratio, mIconXOffset + middleX, mIconYOffset + middleY);
                    canvas.setMatrix(scaleMatrix);
                    mWeatherIconPaint.setFilterBitmap(true);

                    canvas.drawBitmap(mWeatherIconBitmap, mIconXOffset * ratio, mIconYOffset * ratio, mWeatherIconPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /**
         * initialize the date and time format
         */
        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);

            mTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            mTimeFormat.setCalendar(mCalendar);

            mDate = new Date();
        }
    }
}
