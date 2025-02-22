/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.vc;

import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BluetoothVolumeControl;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.IntStream;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlServiceTest {
    private BluetoothAdapter mAdapter;
    private AttributionSource mAttributionSource;
    private Context mTargetContext;
    private VolumeControlService mService;
    private VolumeControlService.BluetoothVolumeControlBinder mServiceBinder;
    private BluetoothDevice mDevice;
    private BluetoothDevice mDeviceTwo;
    private HashMap<BluetoothDevice, LinkedBlockingQueue<Intent>> mDeviceQueueMap;
    private static final int TIMEOUT_MS = 1000;
    private static final int BT_LE_AUDIO_MAX_VOL = 255;
    private static final int MEDIA_MIN_VOL = 0;
    private static final int MEDIA_MAX_VOL = 25;
    private static final int CALL_MIN_VOL = 1;
    private static final int CALL_MAX_VOL = 8;

    private BroadcastReceiver mVolumeControlIntentReceiver;

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mAdapterService;
    @Mock private BassClientService mBassClientService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private VolumeControlNativeInterface mNativeInterface;
    @Mock private AudioManager mAudioManager;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private CsipSetCoordinatorService mCsipService;

    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        TestUtils.setAdapterService(mAdapterService);
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAttributionSource = mAdapter.getAttributionSource();

        doReturn(MEDIA_MIN_VOL)
                .when(mAudioManager)
                .getStreamMinVolume(eq(AudioManager.STREAM_MUSIC));
        doReturn(MEDIA_MAX_VOL)
                .when(mAudioManager)
                .getStreamMaxVolume(eq(AudioManager.STREAM_MUSIC));
        doReturn(CALL_MIN_VOL)
                .when(mAudioManager)
                .getStreamMinVolume(eq(AudioManager.STREAM_VOICE_CALL));
        doReturn(CALL_MAX_VOL)
                .when(mAudioManager)
                .getStreamMaxVolume(eq(AudioManager.STREAM_VOICE_CALL));

        VolumeControlNativeInterface.setInstance(mNativeInterface);
        mService = new VolumeControlService(mTargetContext);
        mService.start();
        mService.setAvailable(true);

        mService.mAudioManager = mAudioManager;
        mService.mFactory = mServiceFactory;
        mServiceBinder = (VolumeControlService.BluetoothVolumeControlBinder) mService.initBinder();

        doReturn(mCsipService).when(mServiceFactory).getCsipSetCoordinatorService();
        doReturn(mLeAudioService).when(mServiceFactory).getLeAudioService();
        doReturn(mBassClientService).when(mServiceFactory).getBassClientService();

        // Override the timeout value to speed up the test
        VolumeControlStateMachine.sConnectTimeoutMs = TIMEOUT_MS; // 1s

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothVolumeControl.ACTION_CONNECTION_STATE_CHANGED);

        mVolumeControlIntentReceiver = new VolumeControlIntentReceiver();
        mTargetContext.registerReceiver(mVolumeControlIntentReceiver, filter);

        // Get a device for testing
        mDevice = TestUtils.getTestDevice(mAdapter, 0);
        mDeviceTwo = TestUtils.getTestDevice(mAdapter, 1);
        mDeviceQueueMap = new HashMap<>();
        mDeviceQueueMap.put(mDevice, new LinkedBlockingQueue<>());
        mDeviceQueueMap.put(mDeviceTwo, new LinkedBlockingQueue<>());
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[] {BluetoothUuid.VOLUME_CONTROL})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
    }

    @After
    public void tearDown() throws Exception {
        if (mService == null) {
            return;
        }

        mService.stop();
        VolumeControlNativeInterface.setInstance(null);
        mTargetContext.unregisterReceiver(mVolumeControlIntentReceiver);
        mDeviceQueueMap.clear();
        TestUtils.clearAdapterService(mAdapterService);
        reset(mAudioManager);
    }

    private class VolumeControlIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Assert.assertNotNull(device);
                LinkedBlockingQueue<Intent> queue = mDeviceQueueMap.get(device);
                Assert.assertNotNull(queue);
                queue.put(intent);
            } catch (InterruptedException e) {
                Assert.fail("Cannot add Intent to the Connection State queue: " + e.getMessage());
            }
        }
    }

    private void verifyConnectionStateIntent(
            int timeoutMs, BluetoothDevice device, int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mDeviceQueueMap.get(device));
        Assert.assertNotNull(intent);
        Assert.assertEquals(
                BluetoothVolumeControl.ACTION_CONNECTION_STATE_CHANGED, intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(newState, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertEquals(
                prevState, intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1));
    }

    private void verifyNoConnectionStateIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForNoIntent(timeoutMs, mDeviceQueueMap.get(device));
        Assert.assertNull(intent);
    }

    /** Test getting VolumeControl Service: getVolumeControlService() */
    @Test
    public void testGetVolumeControlService() {
        Assert.assertEquals(mService, VolumeControlService.getVolumeControlService());
    }

    /** Test stop VolumeControl Service */
    @Test
    public void testStopVolumeControlService() throws Exception {
        // Prepare: connect
        connectDevice(mDevice);
        // VolumeControl Service is already running: test stop().
        // Note: must be done on the main thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync(mService::stop);
        // Try to restart the service. Note: must be done on the main thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync(mService::start);
    }

    /** Test get/set policy for BluetoothDevice */
    @Test
    public void testGetSetPolicy() {
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        Assert.assertEquals(
                "Initial device policy",
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                mService.getConnectionPolicy(mDevice));

        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        Assert.assertEquals(
                "Setting device policy to POLICY_FORBIDDEN",
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                mService.getConnectionPolicy(mDevice));

        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        Assert.assertEquals(
                "Setting device policy to POLICY_ALLOWED",
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                mService.getConnectionPolicy(mDevice));
    }

    /** Test if getProfileConnectionPolicy works after the service is stopped. */
    @Test
    public void testGetPolicyAfterStopped() throws Exception {
        mService.stop();
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        int policy = mServiceBinder.getConnectionPolicy(mDevice, mAttributionSource);
        Assert.assertEquals(
                "Initial device policy", BluetoothProfile.CONNECTION_POLICY_UNKNOWN, policy);
    }

    /** Test okToConnect method using various test cases */
    @Test
    public void testOkToConnect() {
        int badPolicyValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_NONE, badPolicyValue, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                false);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDING, badPolicyValue, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                true);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDED,
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                true);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDED, badPolicyValue, false);
        testOkToConnectCase(
                mDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(
                mDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mDevice, badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mDevice, badBondState, badPolicyValue, false);
    }

    /**
     * Test that an outgoing connection to device that does not have Volume Control UUID is rejected
     */
    @Test
    public void testOutgoingConnectMissingVolumeControlUuid() {
        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mService.connect(mDevice));
    }

    /** Test that an outgoing connection to device that have Volume Control UUID is successful */
    @Test
    public void testOutgoingConnectDisconnectExistingVolumeControlUuid() throws Exception {
        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        // Return Volume Control UUID
        doReturn(new ParcelUuid[] {BluetoothUuid.VOLUME_CONTROL})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect expected to succeed", mService.connect(mDevice));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);

        // Send a disconnect request
        Assert.assertTrue("Disconnect expected to succeed", mService.disconnect(mDevice));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
    }

    /** Test that an outgoing connection to device with POLICY_FORBIDDEN is rejected */
    @Test
    public void testOutgoingConnectPolicyForbidden() {
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        // Set the device policy to POLICY_FORBIDDEN so connect() should fail
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mService.connect(mDevice));
    }

    /** Test that an outgoing connection times out */
    @Test
    public void testOutgoingConnectTimeout() throws Exception {
        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect failed", mService.connect(mDevice));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(mDevice));

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(
                VolumeControlStateMachine.sConnectTimeoutMs * 2,
                mDevice,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);

        int state = mServiceBinder.getConnectionState(mDevice, mAttributionSource);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED, state);
    }

    /**
     * Test that only CONNECTION_STATE_CONNECTED or CONNECTION_STATE_CONNECTING Volume Control stack
     * events will create a state machine.
     */
    @Test
    public void testCreateStateMachineStackEvents() {
        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        // stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(
                BluetoothProfile.STATE_DISCONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));
        mService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        Assert.assertFalse(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_DISCONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));
        mService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        Assert.assertFalse(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_DISCONNECTING - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_DISCONNECTING);
        Assert.assertEquals(
                BluetoothProfile.STATE_DISCONNECTED, mService.getConnectionState(mDevice));
        Assert.assertFalse(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mDevice, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_DISCONNECTED, mService.getConnectionState(mDevice));
        Assert.assertFalse(mService.getDevices().contains(mDevice));
    }

    /**
     * Test that a CONNECTION_STATE_DISCONNECTED Volume Control stack event will remove the state
     * machine only if the device is unbond.
     */
    @Test
    public void testDeleteStateMachineDisconnectEvents() {
        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        // stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(
                BluetoothProfile.STATE_DISCONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_CONNECTING - state machine remains
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // device bond state marked as unbond - state machine is not removed
        doReturn(BluetoothDevice.BOND_NONE)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine is removed
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(
                BluetoothProfile.STATE_DISCONNECTED, mService.getConnectionState(mDevice));
        Assert.assertFalse(mService.getDevices().contains(mDevice));
    }

    /** Test that various Volume Control stack events will broadcast related states. */
    @Test
    public void testVolumeControlStackEvents() {
        int group_id = -1;
        int volume = 6;
        int flags = 0;
        boolean mute = false;
        boolean isAutonomous = false;

        // Send a message to trigger volume state changed broadcast
        generateVolumeStateChanged(mDevice, group_id, volume, flags, mute, isAutonomous);
    }

    int getLeAudioVolume(int index, int minIndex, int maxIndex, int streamType) {
        // Note: This has to be the same as mBtHelper.setLeAudioVolume()
        return (int) Math.round((double) index * BT_LE_AUDIO_MAX_VOL / maxIndex);
    }

    void testVolumeCalculations(int streamType, int minIdx, int maxIdx) {
        // Send a message to trigger volume state changed broadcast
        final VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED);
        stackEvent.device = null;
        stackEvent.valueInt1 = 1; // groupId
        stackEvent.valueBool1 = false; // isMuted
        stackEvent.valueBool2 = true; // isAutonomous

        IntStream.range(minIdx, maxIdx)
                .forEach(
                        idx -> {
                            // Given the reference volume index, set the LeAudio Volume
                            stackEvent.valueInt2 =
                                    getLeAudioVolume(idx, minIdx, maxIdx, streamType);
                            mService.messageFromNative(stackEvent);

                            // Verify that setting LeAudio Volume, sets the original volume index to
                            // Audio FW
                            verify(mAudioManager, times(1))
                                    .setStreamVolume(eq(streamType), eq(idx), anyInt());
                        });
    }

    @Test
    public void testAutonomousVolumeStateChange() {
        // TODO: b/329163385 - This test should be modified to run without having to set the flag to
        // a specific value
        mSetFlagsRule.disableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();
        testVolumeCalculations(AudioManager.STREAM_VOICE_CALL, CALL_MIN_VOL, CALL_MAX_VOL);

        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        testVolumeCalculations(AudioManager.STREAM_MUSIC, MEDIA_MIN_VOL, MEDIA_MAX_VOL);
    }

    /** Test if autonomous Mute/Unmute propagates the event to audio manager. */
    @Test
    public void testAutonomousMuteUnmute() {
        // TODO: b/329163385 - This test should be modified to run without having to set the flag to
        // a specific value
        mSetFlagsRule.disableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);
        int streamType = AudioManager.STREAM_MUSIC;
        int streamVol = getLeAudioVolume(19, MEDIA_MIN_VOL, MEDIA_MAX_VOL, streamType);

        doReturn(false).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));

        // Verify that muting LeAudio device, sets the mute state on the audio device

        generateVolumeStateChanged(null, 1, streamVol, 0, true, true);
        verify(mAudioManager, times(1))
                .adjustStreamVolume(eq(streamType), eq(AudioManager.ADJUST_MUTE), anyInt());

        doReturn(true).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));

        // Verify that unmuting LeAudio device, unsets the mute state on the audio device
        generateVolumeStateChanged(null, 1, streamVol, 0, false, true);
        verify(mAudioManager, times(1))
                .adjustStreamVolume(eq(streamType), eq(AudioManager.ADJUST_UNMUTE), anyInt());
    }

    /** Test Volume Control cache. */
    @Test
    public void testVolumeCache() throws Exception {
        int groupId = 1;
        int volume = 6;

        Assert.assertEquals(-1, mService.getGroupVolume(groupId));
        mServiceBinder.setGroupVolume(groupId, volume, mAttributionSource);

        int groupVolume = mServiceBinder.getGroupVolume(groupId, mAttributionSource);
        Assert.assertEquals(volume, groupVolume);

        volume = 10;
        // Send autonomous volume change.
        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        Assert.assertEquals(volume, mService.getGroupVolume(groupId));
    }

    /** Test Active Group change */
    @Test
    public void testActiveGroupChange() throws Exception {
        // TODO: b/329163385 - This test should be modified to run without having to set the flag to
        // a specific value
        mSetFlagsRule.disableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);
        int groupId_1 = 1;
        int volume_groupId_1 = 6;

        int groupId_2 = 2;
        int volume_groupId_2 = 20;

        Assert.assertEquals(-1, mService.getGroupVolume(groupId_1));
        Assert.assertEquals(-1, mService.getGroupVolume(groupId_2));
        mServiceBinder.setGroupVolume(groupId_1, volume_groupId_1, mAttributionSource);

        mServiceBinder.setGroupVolume(groupId_2, volume_groupId_2, mAttributionSource);

        mServiceBinder.setGroupActive(groupId_1, true, mAttributionSource);

        // Expected index for STREAM_MUSIC
        int expectedVol =
                (int) Math.round((double) (volume_groupId_1 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), eq(expectedVol), anyInt());

        mServiceBinder.setGroupActive(groupId_2, true, mAttributionSource);

        expectedVol =
                (int) Math.round((double) (volume_groupId_2 * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), eq(expectedVol), anyInt());
    }

    /** Test Volume Control Mute cache. */
    @Test
    public void testMuteCache() throws Exception {
        int groupId = 1;
        int volume = 6;

        Assert.assertEquals(false, mService.getGroupMute(groupId));

        // Send autonomous volume change
        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        // Mute
        mServiceBinder.muteGroup(groupId, mAttributionSource);
        Assert.assertEquals(true, mService.getGroupMute(groupId));

        // Make sure the volume is kept even when muted
        Assert.assertEquals(volume, mService.getGroupVolume(groupId));

        // Send autonomous unmute
        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        Assert.assertEquals(false, mService.getGroupMute(groupId));
    }

    /** Test Volume Control with muted stream. */
    @Test
    public void testVolumeChangeWhileMuted() throws Exception {
        int groupId = 1;
        int volume = 6;

        Assert.assertEquals(false, mService.getGroupMute(groupId));

        generateVolumeStateChanged(null, groupId, volume, 0, false, true);

        // Mute
        mService.muteGroup(groupId);
        Assert.assertEquals(true, mService.getGroupMute(groupId));
        verify(mNativeInterface, times(1)).muteGroup(eq(groupId));

        // Make sure the volume is kept even when muted
        doReturn(true).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));
        Assert.assertEquals(volume, mService.getGroupVolume(groupId));

        // Lower the volume and keep it mute
        mService.setGroupVolume(groupId, --volume);
        Assert.assertEquals(true, mService.getGroupMute(groupId));
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(volume));
        verify(mNativeInterface, times(0)).unmuteGroup(eq(groupId));

        // Don't unmute on consecutive calls either
        mService.setGroupVolume(groupId, --volume);
        Assert.assertEquals(true, mService.getGroupMute(groupId));
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(volume));
        verify(mNativeInterface, times(0)).unmuteGroup(eq(groupId));

        // Raise the volume and unmute
        volume += 10; // avoid previous volume levels and simplify mock verification
        doReturn(false).when(mAudioManager).isStreamMute(eq(AudioManager.STREAM_MUSIC));
        mService.setGroupVolume(groupId, ++volume);
        Assert.assertEquals(false, mService.getGroupMute(groupId));
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(volume));
        // Verify the number of unmute calls after the second volume change
        mService.setGroupVolume(groupId, ++volume);
        Assert.assertEquals(false, mService.getGroupMute(groupId));
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(volume));
        // Make sure we unmuted only once
        verify(mNativeInterface, times(1)).unmuteGroup(eq(groupId));
    }

    /** Test if phone will set volume which is read from the buds */
    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES,
        Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_PRIMARY_GROUP_ONLY
    })
    public void testConnectedDeviceWithUserPersistFlagSet() throws Exception {
        int groupId = 1;
        int volumeDevice = 56;
        int volumeDeviceTwo = 100;
        int flags = VolumeControlService.VOLUME_FLAGS_PERSISTED_USER_SET_VOLUME_MASK;
        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        when(mBassClientService.getSyncedBroadcastSinks()).thenReturn(new ArrayList<>());
        // Group is not active unicast and not active primary broadcast, AF will not be notified
        generateVolumeStateChanged(
                mDevice, groupId, volumeDevice, flags, initialMuteState, initialAutonomousFlag);
        verify(mAudioManager, times(0)).setStreamVolume(anyInt(), anyInt(), anyInt());

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(groupId);
        mServiceBinder.setGroupActive(groupId, true, mAttributionSource);
        int expectedAfVol =
                (int) Math.round((double) (volumeDevice * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), eq(expectedAfVol), anyInt());

        // Connect second device and read different volume. Expect it will be set to AF and to
        // another set member
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));

        // Group is now active, AF will be notified. Native will take care to sync the volume
        generateVolumeStateChanged(
                mDeviceTwo,
                groupId,
                volumeDeviceTwo,
                flags,
                initialMuteState,
                initialAutonomousFlag);
        expectedAfVol =
                (int) Math.round((double) (volumeDeviceTwo * MEDIA_MAX_VOL) / BT_LE_AUDIO_MAX_VOL);
        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), eq(expectedAfVol), anyInt());
    }

    private void testConnectedDeviceWithResetFlag(
            int resetVolumeDeviceOne, int resetVolumeDeviceTwo) {
        int groupId = 1;
        int streamVolume = 30;
        int streamMaxVolume = 100;
        int resetFlag = 0;

        boolean initialMuteState = false;
        boolean initialAutonomousFlag = true;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(streamVolume);
        when(mAudioManager.getStreamMaxVolume(anyInt())).thenReturn(streamMaxVolume);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        int expectedAfVol =
                (int) Math.round((double) streamVolume * BT_LE_AUDIO_MAX_VOL / streamMaxVolume);

        // Group is not active, AF will not be notified
        generateVolumeStateChanged(
                mDevice,
                groupId,
                resetVolumeDeviceOne,
                resetFlag,
                initialMuteState,
                initialAutonomousFlag);
        verify(mAudioManager, times(0)).setStreamVolume(anyInt(), anyInt(), anyInt());

        // Make device Active now. This will trigger setting volume to AF
        when(mLeAudioService.getActiveGroupId()).thenReturn(groupId);
        mServiceBinder.setGroupActive(groupId, true, mAttributionSource);

        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), eq(streamVolume), anyInt());
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(expectedAfVol));

        // Connect second device and read different volume. Expect it will be set to AF and to
        // another set member
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));

        // Group is now active, AF will be notified. Native will take care to sync the volume
        generateVolumeStateChanged(
                mDeviceTwo,
                groupId,
                resetVolumeDeviceTwo,
                resetFlag,
                initialMuteState,
                initialAutonomousFlag);

        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), anyInt(), anyInt());
        verify(mNativeInterface, times(2)).setGroupVolume(eq(groupId), eq(expectedAfVol));
    }

    /** Test if phone will set volume which is read from the buds */
    @Test
    public void testConnectedDeviceWithResetFlagSetWithNonZeroVolume() throws Exception {
        testConnectedDeviceWithResetFlag(56, 100);
    }

    /** Test if phone will set volume to buds which has no volume */
    @Test
    public void testConnectedDeviceWithResetFlagSetWithZeroVolume() throws Exception {
        testConnectedDeviceWithResetFlag(0, 0);
    }

    /**
     * Test setting volume for a group member who connects after the volume level for a group was
     * already changed and cached.
     */
    @Test
    public void testLateConnectingDevice() throws Exception {
        int groupId = 1;
        int groupVolume = 56;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        mService.setGroupVolume(groupId, groupVolume);
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(groupVolume));
        verify(mNativeInterface, times(0)).setVolume(eq(mDeviceTwo), eq(groupVolume));

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(groupVolume));
    }

    /**
     * Test setting volume for a new group member who is discovered after the volume level for a
     * group was already changed and cached.
     */
    @Test
    public void testLateDiscoveredGroupMember() throws Exception {
        int groupId = 1;
        int groupVolume = 56;

        // For now only one device is in the group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(-1);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // Set the group volume
        mService.setGroupVolume(groupId, groupVolume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        verify(mNativeInterface, times(0)).setVolume(eq(mDeviceTwo), eq(groupVolume));

        // But gets the volume when it becomes the group member
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        mService.handleGroupNodeAdded(groupId, mDeviceTwo);
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(groupVolume));
    }

    /**
     * Test setting volume to 0 for a group member who connects after the volume level for a group
     * was already changed and cached. LeAudio has no knowledge of mute for anything else than
     * telephony, thus setting volume level to 0 is considered as muting.
     */
    @Test
    public void testMuteLateConnectingDevice() throws Exception {
        int groupId = 1;
        int volume = 100;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(groupId, volume);

        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(volume));
        verify(mNativeInterface, times(0)).setVolume(eq(mDeviceTwo), eq(volume));
        // Check if it was muted
        verify(mNativeInterface, times(1)).muteGroup(eq(groupId));

        Assert.assertEquals(true, mService.getGroupMute(groupId));

        // Verify that second device gets the proper group volume level when connected
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(volume));
        // Check if new device was muted
        verify(mNativeInterface, times(1)).mute(eq(mDeviceTwo));
    }

    /**
     * Test setting volume to 0 for a new group member who is discovered after the volume level for
     * a group was already changed and cached. LeAudio has no knowledge of mute for anything else
     * than telephony, thus setting volume level to 0 is considered as muting.
     */
    @Test
    public void testMuteLateDiscoveredGroupMember() throws Exception {
        int groupId = 1;
        int volume = 100;

        // For now only one device is in the group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(-1);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // Set the initial volume and mute conditions
        doReturn(true).when(mAudioManager).isStreamMute(anyInt());
        mService.setGroupVolume(groupId, volume);

        // Verify that second device will not get the group volume level if it is not a group member
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        verify(mNativeInterface, times(0)).setVolume(eq(mDeviceTwo), eq(volume));
        // Check if it was not muted
        verify(mNativeInterface, times(0)).mute(eq(mDeviceTwo));

        // But gets the volume when it becomes the group member
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);
        mService.handleGroupNodeAdded(groupId, mDeviceTwo);
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(volume));
        verify(mNativeInterface, times(1)).mute(eq(mDeviceTwo));
    }

    @Test
    public void testServiceBinderGetDevicesMatchingConnectionStates() throws Exception {
        List<BluetoothDevice> devices =
                mServiceBinder.getDevicesMatchingConnectionStates(null, mAttributionSource);
        Assert.assertEquals(0, devices.size());
    }

    @Test
    public void testServiceBinderSetConnectionPolicy() throws Exception {
        Assert.assertTrue(
                mServiceBinder.setConnectionPolicy(
                        mDevice, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, mAttributionSource));
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice,
                        BluetoothProfile.VOLUME_CONTROL,
                        BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
    }

    @Test
    public void testServiceBinderVolumeOffsetMethods() throws Exception {
        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice, 2);

        Assert.assertTrue(mServiceBinder.isVolumeOffsetAvailable(mDevice, mAttributionSource));

        int numberOfInstances =
                mServiceBinder.getNumberOfVolumeOffsetInstances(mDevice, mAttributionSource);
        Assert.assertEquals(2, numberOfInstances);

        int id = 1;
        int volumeOffset = 100;
        mServiceBinder.setVolumeOffset(mDevice, id, volumeOffset, mAttributionSource);
        verify(mNativeInterface).setExtAudioOutVolumeOffset(mDevice, id, volumeOffset);
    }

    @Test
    public void testServiceBinderSetDeviceVolumeMethods() throws Exception {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);

        int groupId = 1;
        int groupVolume = 56;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        // Both devices are in the same group
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mDeviceTwo)).thenReturn(groupId);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        mServiceBinder.setDeviceVolume(mDevice, groupVolume, true, mAttributionSource);
        verify(mNativeInterface).setGroupVolume(groupId, groupVolume);
        Assert.assertEquals(groupVolume, mService.getGroupVolume(groupId));

        mServiceBinder.setDeviceVolume(mDevice, deviceOneVolume, false, mAttributionSource);
        verify(mNativeInterface).setVolume(mDevice, deviceOneVolume);
        Assert.assertEquals(deviceOneVolume, mService.getDeviceVolume(mDevice));
        Assert.assertNotEquals(deviceOneVolume, mService.getDeviceVolume(mDeviceTwo));

        mServiceBinder.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false, mAttributionSource);
        verify(mNativeInterface).setVolume(mDeviceTwo, deviceTwoVolume);
        Assert.assertEquals(deviceTwoVolume, mService.getDeviceVolume(mDeviceTwo));
        Assert.assertNotEquals(deviceTwoVolume, mService.getDeviceVolume(mDevice));
    }

    @Test
    public void testServiceBinderRegisterUnregisterCallback() throws Exception {
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());

            mService.unregisterCallback(callback);
            Assert.assertEquals(size, mService.mCallbacks.getRegisteredCallbackCount());
        }
    }

    @Test
    public void testServiceBinderRegisterCallbackWhenDeviceAlreadyConnected() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_MULTIPLE_VOCS_INSTANCES_API);

        int groupId = 1;
        int groupVolume = 56;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 2);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        mService.setGroupVolume(groupId, groupVolume);
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(groupVolume));
        verify(mNativeInterface, times(0)).setVolume(eq(mDeviceTwo), eq(groupVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(groupVolume));

        // Generate events for both devices
        generateDeviceOffsetChangedMessageFromNative(mDevice, 1, 100);
        generateDeviceLocationChangedMessageFromNative(mDevice, 1, 1);
        final String testDevice1Desc1 = "testDevice1Desc1";
        generateDeviceDescriptionChangedMessageFromNative(mDevice, 1, testDevice1Desc1);

        generateDeviceOffsetChangedMessageFromNative(mDevice, 2, 200);
        generateDeviceLocationChangedMessageFromNative(mDevice, 2, 2);
        final String testDevice1Desc2 = "testDevice1Desc2";
        generateDeviceDescriptionChangedMessageFromNative(mDevice, 2, testDevice1Desc2);

        generateDeviceOffsetChangedMessageFromNative(mDeviceTwo, 1, 250);
        generateDeviceLocationChangedMessageFromNative(mDeviceTwo, 1, 3);
        final String testDevice2Desc = "testDevice2Desc";
        generateDeviceDescriptionChangedMessageFromNative(mDeviceTwo, 1, testDevice2Desc);

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());
        }

        verify(callback).onVolumeOffsetChanged(eq(mDevice), eq(1), eq(100));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice), eq(1), eq(1));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice), eq(1), eq(testDevice1Desc1));

        verify(callback).onVolumeOffsetChanged(eq(mDevice), eq(2), eq(200));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice), eq(2), eq(2));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice), eq(2), eq(testDevice1Desc2));

        verify(callback).onVolumeOffsetChanged(eq(mDeviceTwo), eq(1), eq(250));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDeviceTwo), eq(1), eq(3));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDeviceTwo), eq(1), eq(testDevice2Desc));

        generateDeviceOffsetChangedMessageFromNative(mDevice, 1, 50);
        generateDeviceLocationChangedMessageFromNative(mDevice, 1, 0);
        final String testDevice1Desc3 = "testDevice1Desc3";
        generateDeviceDescriptionChangedMessageFromNative(mDevice, 1, testDevice1Desc3);

        verify(callback).onVolumeOffsetChanged(eq(mDevice), eq(1), eq(50));
        verify(callback).onVolumeOffsetAudioLocationChanged(eq(mDevice), eq(1), eq(0));
        verify(callback)
                .onVolumeOffsetAudioDescriptionChanged(eq(mDevice), eq(1), eq(testDevice1Desc3));
    }

    @Test
    public void testServiceBinderRegisterVolumeChangedCallbackWhenDeviceAlreadyConnected()
            throws Exception {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);
        int groupId = 1;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));
        mService.setDeviceVolume(mDevice, deviceOneVolume, false);
        verify(mNativeInterface, times(1)).setVolume(eq(mDevice), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        mService.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false);
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(deviceTwoVolume));

        // Both devices are in the same group
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mDeviceTwo)).thenReturn(groupId);

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());
        }

        verify(callback, times(1)).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback, times(1)).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceTwoVolume));
    }

    @Test
    public void testServiceBinderTestNotifyNewRegisteredCallback() throws Exception {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);
        int groupId = 1;
        int deviceOneVolume = 46;
        int deviceTwoVolume = 36;

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));
        mService.setDeviceVolume(mDevice, deviceOneVolume, false);
        verify(mNativeInterface, times(1)).setVolume(eq(mDevice), eq(deviceOneVolume));

        // Verify that second device gets the proper group volume level when connected
        generateDeviceAvailableMessageFromNative(mDeviceTwo, 1);
        generateConnectionMessageFromNative(
                mDeviceTwo, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDeviceTwo));
        Assert.assertTrue(mService.getDevices().contains(mDeviceTwo));
        mService.setDeviceVolume(mDeviceTwo, deviceTwoVolume, false);
        verify(mNativeInterface, times(1)).setVolume(eq(mDeviceTwo), eq(deviceTwoVolume));

        // Both devices are in the same group
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mDeviceTwo)).thenReturn(groupId);

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        int size;
        synchronized (mService.mCallbacks) {
            size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());
        }

        IBluetoothVolumeControlCallback callback_new_client =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder_new_client = Mockito.mock(Binder.class);
        when(callback_new_client.asBinder()).thenReturn(binder_new_client);

        mServiceBinder.notifyNewRegisteredCallback(callback_new_client, mAttributionSource);
        synchronized (mService.mCallbacks) {
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());
        }

        // This shall be done only once after mService.registerCallback
        verify(callback, times(1)).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback, times(1)).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceTwoVolume));

        // This shall be done only once after mServiceBinder.updateNewRegisteredCallback
        verify(callback_new_client, times(1))
                .onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback_new_client, times(1))
                .onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceTwoVolume));
    }

    @Test
    public void testServiceBinderMuteMethods() throws Exception {
        mServiceBinder.mute(mDevice, mAttributionSource);
        verify(mNativeInterface).mute(mDevice);

        mServiceBinder.unmute(mDevice, mAttributionSource);
        verify(mNativeInterface).unmute(mDevice);

        int groupId = 1;
        mServiceBinder.muteGroup(groupId, mAttributionSource);
        verify(mNativeInterface).muteGroup(groupId);

        mServiceBinder.unmuteGroup(groupId, mAttributionSource);
        verify(mNativeInterface).unmuteGroup(groupId);
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        connectDevice(mDevice);

        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
    }

    /** Test Volume Control changed callback. */
    @Test
    public void testVolumeControlChangedCallback() throws Exception {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES);

        int groupId = 1;
        int groupVolume = 56;
        int deviceOneVolume = 46;

        // Both devices are in the same group
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mDeviceTwo)).thenReturn(groupId);

        // Send a message to trigger connection completed
        generateDeviceAvailableMessageFromNative(mDevice, 2);

        mServiceBinder.setDeviceVolume(mDevice, groupVolume, true, mAttributionSource);
        verify(mNativeInterface, times(1)).setGroupVolume(eq(groupId), eq(groupVolume));

        // Register callback and verify it is called with known devices
        IBluetoothVolumeControlCallback callback =
                Mockito.mock(IBluetoothVolumeControlCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);

        synchronized (mService.mCallbacks) {
            int size = mService.mCallbacks.getRegisteredCallbackCount();
            mService.registerCallback(callback);
            Assert.assertEquals(size + 1, mService.mCallbacks.getRegisteredCallbackCount());
        }

        when(mLeAudioService.getGroupDevices(groupId))
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));

        // Send group volume change.
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);

        verify(callback).onDeviceVolumeChanged(eq(mDeviceTwo), eq(groupVolume));
        verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(groupVolume));

        // Send device volume change only for one device
        generateVolumeStateChanged(mDevice, -1, deviceOneVolume, 0, false, false);

        verify(callback).onDeviceVolumeChanged(eq(mDevice), eq(deviceOneVolume));
        verify(callback, never()).onDeviceVolumeChanged(eq(mDeviceTwo), eq(deviceOneVolume));
    }

    /** Test Volume Control changed for broadcast primary group. */
    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_FOR_CONNECTED_DEVICES,
        Flags.FLAG_LEAUDIO_BROADCAST_VOLUME_CONTROL_PRIMARY_GROUP_ONLY
    })
    public void testVolumeControlChangedForBroadcastPrimaryGroup() throws Exception {
        int groupId = 1;
        int groupVolume = 30;

        // Both devices are in the same group
        when(mCsipService.getGroupId(mDevice, BluetoothUuid.CAP)).thenReturn(groupId);
        when(mCsipService.getGroupId(mDeviceTwo, BluetoothUuid.CAP)).thenReturn(groupId);

        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(groupVolume);

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(
                        any(BluetoothDevice.class), eq(BluetoothProfile.VOLUME_CONTROL)))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(any(BluetoothDevice.class));

        generateDeviceAvailableMessageFromNative(mDevice, 1);
        generateConnectionMessageFromNative(
                mDevice, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));
        Assert.assertTrue(mService.getDevices().contains(mDevice));

        // Make active group as null and broadcast active
        when(mLeAudioService.getActiveGroupId()).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mBassClientService.getSyncedBroadcastSinks()).thenReturn(new ArrayList<>());

        // Group is broadcast primary group, AF will not be notified
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);
        verify(mAudioManager, times(0)).setStreamVolume(anyInt(), anyInt(), anyInt());

        // Make active group as null and broadcast active
        when(mLeAudioService.getActiveGroupId()).thenReturn(LE_AUDIO_GROUP_ID_INVALID);
        when(mBassClientService.getSyncedBroadcastSinks())
                .thenReturn(Arrays.asList(mDevice, mDeviceTwo));
        when(mLeAudioService.getGroupId(mDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mDeviceTwo)).thenReturn(groupId);
        when(mLeAudioService.isPrimaryGroup(groupId)).thenReturn(true);
        // Group is not broadcast primary group, AF will not be notified
        generateVolumeStateChanged(null, groupId, groupVolume, 0, false, true);

        verify(mAudioManager, times(1)).setStreamVolume(anyInt(), anyInt(), anyInt());
    }

    private void connectDevice(BluetoothDevice device) throws Exception {
        VolumeControlStackEvent connCompletedEvent;

        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        // Update the device policy so okToConnect() returns true
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectVolumeControl(device);
        doReturn(true).when(mNativeInterface).disconnectVolumeControl(device);

        // Send a connect request
        Assert.assertTrue("Connect failed", mService.connect(device));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                device,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(device));

        // Send a message to trigger connection completed
        connCompletedEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = VolumeControlStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                device,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(device));

        // Verify that the device is in the list of connected devices
        List<BluetoothDevice> connectedDevices =
                mServiceBinder.getConnectedDevices(mAttributionSource);
        Assert.assertTrue(connectedDevices.contains(device));
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            Assert.assertTrue(connectedDevices.contains(prevDevice));
        }
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState, int oldConnectionState) {
        VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(TIMEOUT_MS, device, newConnectionState, oldConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState) {
        VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyNoConnectionStateIntent(TIMEOUT_MS, device);
    }

    private void generateDeviceAvailableMessageFromNative(
            BluetoothDevice device, int numberOfExtOffsets) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        event.device = device;
        event.valueInt1 = numberOfExtOffsets; // number of external outputs
        mService.messageFromNative(event);
    }

    private void generateVolumeStateChanged(
            BluetoothDevice device,
            int group_id,
            int volume,
            int flags,
            boolean mute,
            boolean isAutonomous) {
        VolumeControlStackEvent stackEvent =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = group_id;
        stackEvent.valueInt2 = volume;
        stackEvent.valueInt3 = flags;
        stackEvent.valueBool1 = mute;
        stackEvent.valueBool2 = isAutonomous;
        mService.messageFromNative(stackEvent);
    }

    private void generateDeviceOffsetChangedMessageFromNative(
            BluetoothDevice device, int extOffsetIndex, int offset) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED);
        event.device = device;
        event.valueInt1 = extOffsetIndex; // external output index
        event.valueInt2 = offset; // offset value
        mService.messageFromNative(event);
    }

    private void generateDeviceLocationChangedMessageFromNative(
            BluetoothDevice device, int extOffsetIndex, int location) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED);
        event.device = device;
        event.valueInt1 = extOffsetIndex; // external output index
        event.valueInt2 = location; // location
        mService.messageFromNative(event);
    }

    private void generateDeviceDescriptionChangedMessageFromNative(
            BluetoothDevice device, int extOffsetIndex, String description) {
        // Send a message to trigger connection completed
        VolumeControlStackEvent event =
                new VolumeControlStackEvent(
                        VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED);
        event.device = device;
        event.valueInt1 = extOffsetIndex; // external output index
        event.valueString1 = description; // description
        mService.messageFromNative(event);
    }

    /**
     * Helper function to test okToConnect() method
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param policy value, could be invalid
     * @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(
            BluetoothDevice device, int bondState, int policy, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.VOLUME_CONTROL))
                .thenReturn(policy);
        Assert.assertEquals(expected, mService.okToConnect(device));
    }
}
