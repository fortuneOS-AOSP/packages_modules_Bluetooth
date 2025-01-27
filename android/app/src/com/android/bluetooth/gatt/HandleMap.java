/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.bluetooth.gatt;

import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class HandleMap {
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "HandleMap";

    public static final int TYPE_UNDEFINED = 0;
    public static final int TYPE_SERVICE = 1;
    public static final int TYPE_CHARACTERISTIC = 2;
    public static final int TYPE_DESCRIPTOR = 3;

    static class Entry {
        public int serverIf = 0;
        public int type = TYPE_UNDEFINED;
        public int handle = 0;
        public UUID uuid = null;
        public int instance = 0;
        public int serviceType = 0;
        public int serviceHandle = 0;
        public int charHandle = 0;
        public boolean started = false;
        public boolean advertisePreferred = false;

        Entry(int serverIf, int handle, UUID uuid, int serviceType, int instance) {
            this.serverIf = serverIf;
            this.type = TYPE_SERVICE;
            this.handle = handle;
            this.uuid = uuid;
            this.instance = instance;
            this.serviceType = serviceType;
        }

        Entry(
                int serverIf,
                int handle,
                UUID uuid,
                int serviceType,
                int instance,
                boolean advertisePreferred) {
            this.serverIf = serverIf;
            this.type = TYPE_SERVICE;
            this.handle = handle;
            this.uuid = uuid;
            this.instance = instance;
            this.serviceType = serviceType;
            this.advertisePreferred = advertisePreferred;
        }

        Entry(int serverIf, int type, int handle, UUID uuid, int serviceHandle) {
            this.serverIf = serverIf;
            this.type = type;
            this.handle = handle;
            this.uuid = uuid;
            this.serviceHandle = serviceHandle;
        }

        Entry(int serverIf, int type, int handle, UUID uuid, int serviceHandle, int charHandle) {
            this.serverIf = serverIf;
            this.type = type;
            this.handle = handle;
            this.uuid = uuid;
            this.serviceHandle = serviceHandle;
            this.charHandle = charHandle;
        }
    }

    static class RequestData {
        int mConnId;
        int mHandle;

        RequestData(int connId, int handle) {
            mConnId = connId;
            mHandle = handle;
        }
    }

    List<Entry> mEntries = null;
    Map<Integer, RequestData> mRequestMap = null;
    int mLastCharacteristic = 0;

    HandleMap() {
        mEntries = new CopyOnWriteArrayList<Entry>();
        mRequestMap = new ConcurrentHashMap<Integer, RequestData>();
    }

    void clear() {
        mEntries.clear();
        mRequestMap.clear();
    }

    void addService(
            int serverIf,
            int handle,
            UUID uuid,
            int serviceType,
            int instance,
            boolean advertisePreferred) {
        mEntries.add(new Entry(serverIf, handle, uuid, serviceType, instance, advertisePreferred));
    }

    void addCharacteristic(int serverIf, int handle, UUID uuid, int serviceHandle) {
        mLastCharacteristic = handle;
        mEntries.add(new Entry(serverIf, TYPE_CHARACTERISTIC, handle, uuid, serviceHandle));
    }

    void addDescriptor(int serverIf, int handle, UUID uuid, int serviceHandle) {
        mEntries.add(
                new Entry(
                        serverIf,
                        TYPE_DESCRIPTOR,
                        handle,
                        uuid,
                        serviceHandle,
                        mLastCharacteristic));
    }

    void setStarted(int serverIf, int handle, boolean started) {
        for (Entry entry : mEntries) {
            if (entry.type != TYPE_SERVICE
                    || entry.serverIf != serverIf
                    || entry.handle != handle) {
                continue;
            }

            entry.started = started;
            return;
        }
    }

    Entry getByHandle(int handle) {
        for (Entry entry : mEntries) {
            if (entry.handle == handle) {
                return entry;
            }
        }
        Log.e(TAG, "getByHandle() - Handle " + handle + " not found!");
        return null;
    }

    boolean checkServiceExists(UUID uuid, int handle) {
        for (Entry entry : mEntries) {
            if (entry.type == TYPE_SERVICE && entry.handle == handle && entry.uuid.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    void deleteService(int serverIf, int serviceHandle) {
        mEntries.removeIf(
                entry ->
                        ((entry.serverIf == serverIf)
                                && (entry.handle == serviceHandle
                                        || entry.serviceHandle == serviceHandle)));
    }

    List<Entry> getEntries() {
        return mEntries;
    }

    void addRequest(int connId, int requestId, int handle) {
        mRequestMap.put(requestId, new RequestData(connId, handle));
    }

    void deleteRequest(int requestId) {
        mRequestMap.remove(requestId);
    }

    Entry getByRequestId(int requestId) {
        Integer handle = null;
        RequestData data = mRequestMap.get(requestId);
        if (data != null) {
            handle = data.mHandle;
        }

        if (handle == null) {
            Log.e(TAG, "getByRequestId() - Request ID " + requestId + " not found!");
            return null;
        }
        return getByHandle(handle);
    }

    RequestData getRequestDataByRequestId(int requestId) {
        RequestData data = mRequestMap.get(requestId);
        if (data == null) {
            Log.e(TAG, "getRequestDataByRequestId() - Request ID " + requestId + " not found!");
        } else {
            Log.d(
                    TAG,
                    ("getRequestDataByRequestId(), requestId=" + requestId)
                            + (", connId=" + data.mConnId + ",handle=" + data.mHandle));
        }

        return data;
    }

    /** Logs debug information. */
    void dump(StringBuilder sb) {
        sb.append("  Entries: ").append(mEntries.size()).append("\n");
        sb.append("  Requests: ").append(mRequestMap.size()).append("\n");

        for (Entry entry : mEntries) {
            sb.append("  ").append(entry.serverIf).append(": [").append(entry.handle).append("] ");
            switch (entry.type) {
                case TYPE_SERVICE:
                    sb.append("Service ").append(entry.uuid);
                    sb.append(", started ").append(entry.started);
                    break;

                case TYPE_CHARACTERISTIC:
                    sb.append("  Characteristic ").append(entry.uuid);
                    break;

                case TYPE_DESCRIPTOR:
                    sb.append("    Descriptor ").append(entry.uuid);
                    break;
            }

            sb.append("\n");
        }
    }
}
