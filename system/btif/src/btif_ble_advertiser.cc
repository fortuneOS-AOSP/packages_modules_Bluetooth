/******************************************************************************
 *
 *  Copyright (C) 2016 Google Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#define LOG_TAG "bt_btif_ble_advertiser"

#include <hardware/bluetooth.h>
#include <hardware/bt_gatt.h>

#include <base/bind.h>
#include <vector>

#include "ble_advertiser.h"
#include "bta_closure_api.h"
#include "btif_common.h"

using base::Bind;
using base::Owned;
using std::vector;

extern bt_status_t do_in_jni_thread(const base::Closure& task);

namespace {

template <typename T>
class OwnedArrayWrapper {
 public:
  explicit OwnedArrayWrapper(T* o) : ptr_(o) {}
  ~OwnedArrayWrapper() { delete[] ptr_; }
  T* get() const { return ptr_; }
  OwnedArrayWrapper(OwnedArrayWrapper&& other) {
    ptr_ = other.ptr_;
    other.ptr_ = NULL;
  }

 private:
  mutable T* ptr_;
};

template <typename T>
T* Unwrap(const OwnedArrayWrapper<T>& o) {
  return o.get();
}

template <typename T>
static inline OwnedArrayWrapper<T> OwnedArray(T* o) {
  return OwnedArrayWrapper<T>(o);
}

/* return the actual power in dBm based on the mapping in config file */
int8_t ble_tx_power[BTM_BLE_ADV_TX_POWER_MAX + 1] = BTM_BLE_ADV_TX_POWER;
int8_t ble_map_adv_tx_power(int tx_power_index) {
  if (0 <= tx_power_index && tx_power_index < BTM_BLE_ADV_TX_POWER_MAX)
    return (int8_t)ble_tx_power[tx_power_index];
  return 0;
}

void bta_adv_set_data_cback(tBTA_STATUS call_status) {}

class BleAdvertiserInterfaceImpl : public BleAdvertiserInterface {
  ~BleAdvertiserInterfaceImpl(){};

  void RegisterAdvertiserCb(
      base::Callback<void(uint8_t /* adv_id */, uint8_t /* status */)> cb,
      uint8_t advertiser_id, uint8_t status) {
    LOG(INFO) << __func__ << " status: " << +status << " , adveriser_id: " << +advertiser_id;
    do_in_jni_thread(Bind(cb, advertiser_id, status));
  }

  void RegisterAdvertiser(
      base::Callback<void(uint8_t /* advertiser_id */, uint8_t /* status */)>
          cb) override {
    do_in_bta_thread(
        FROM_HERE, Bind(&BleAdvertisingManager::RegisterAdvertiser,
                        base::Unretained(BleAdvertisingManager::Get()),
                        Bind(&BleAdvertiserInterfaceImpl::RegisterAdvertiserCb,
                             base::Unretained(this), cb)));
  }

  void Unregister(uint8_t advertiser_id) override {
    do_in_bta_thread(
        FROM_HERE,
        Bind(&BleAdvertisingManager::Unregister,
             base::Unretained(BleAdvertisingManager::Get()), advertiser_id));
  }

  void SetData(bool set_scan_rsp, vector<uint8_t> data) override {
    uint8_t* data_ptr = nullptr;
    if (data.size()) {
      // base::Owned will free this ptr
      data_ptr = new uint8_t[data.size()];
      memcpy(data_ptr, data.data(), data.size());
    }

    if (!set_scan_rsp) {
      if (data_ptr) {
        do_in_bta_thread(FROM_HERE,
                         Bind(&BTM_BleWriteAdvData, OwnedArray(data_ptr),
                              data.size(), bta_adv_set_data_cback));
      } else {
        do_in_bta_thread(FROM_HERE, Bind(&BTM_BleWriteAdvData, nullptr,
                                         data.size(), bta_adv_set_data_cback));
      }
    } else {
      if (data_ptr) {
        do_in_bta_thread(FROM_HERE,
                         Bind(&BTM_BleWriteScanRsp, OwnedArray(data_ptr),
                              data.size(), bta_adv_set_data_cback));
      } else {
        do_in_bta_thread(FROM_HERE, Bind(&BTM_BleWriteScanRsp, nullptr,
                                         data.size(), bta_adv_set_data_cback));
      }
    }
  }

  void EnableCb(BleAdvertiserCb cb, uint8_t status) {
    LOG(INFO) << __func__ << " status: " << +status;
    do_in_jni_thread(Bind(cb, status));
  }

  void Enable(bool start, BleAdvertiserCb cb) override {
#if (defined(BLE_PERIPHERAL_MODE_SUPPORT) && \
     (BLE_PERIPHERAL_MODE_SUPPORT == true))
    do_in_jni_thread(Bind(&GATT_Listen, start));
#else
    do_in_jni_thread(Bind(&BTM_BleBroadcast, start));
#endif
    cb.Run(BT_STATUS_SUCCESS);
  }

  void MultiAdvSetParametersCb(BleAdvertiserCb cb, uint8_t status) {
    LOG(INFO) << __func__ << " status: " << +status ;
    do_in_jni_thread(Bind(cb, status));
  }

  virtual void MultiAdvSetParameters(int advertiser_id, int min_interval,
                                     int max_interval, int adv_type,
                                     int chnl_map, int tx_power,
                                     BleAdvertiserCb cb) {
    tBTM_BLE_ADV_PARAMS* params = new tBTM_BLE_ADV_PARAMS;
    params->adv_int_min = min_interval;
    params->adv_int_max = max_interval;
    params->adv_type = adv_type;
    params->channel_map = chnl_map;
    params->adv_filter_policy = 0;
    params->tx_power = ble_map_adv_tx_power(tx_power);

    do_in_bta_thread(
        FROM_HERE,
        Bind(&BleAdvertisingManager::SetParameters,
             base::Unretained(BleAdvertisingManager::Get()), advertiser_id,
             base::Owned(params),
             Bind(&BleAdvertiserInterfaceImpl::MultiAdvSetParametersCb,
                  base::Unretained(this), cb)));
  }

  void MultiAdvSetInstDataCb(BleAdvertiserCb cb, uint8_t advertiser_id,
                             uint8_t status) {
    do_in_jni_thread(Bind(cb, status));
  }

  void MultiAdvSetInstData(int advertiser_id, bool set_scan_rsp,
                           vector<uint8_t> data, BleAdvertiserCb cb) override {
    do_in_bta_thread(
        FROM_HERE, Bind(&BleAdvertisingManager::SetData,
                        base::Unretained(BleAdvertisingManager::Get()),
                        advertiser_id, set_scan_rsp, std::move(data),
                        Bind(&BleAdvertiserInterfaceImpl::MultiAdvSetInstDataCb,
                             base::Unretained(this), cb, advertiser_id)));
  }

  void MultiAdvEnableTimeoutCb(BleAdvertiserCb cb, uint8_t status) {
    do_in_jni_thread(Bind(cb, status));
  }

  void MultiAdvEnableCb(BleAdvertiserCb cb, uint8_t status) {
    do_in_jni_thread(Bind(cb, status));
  }

  void MultiAdvEnable(uint8_t advertiser_id, bool enable, BleAdvertiserCb cb,
                      int timeout_s, BleAdvertiserCb timeout_cb) override {
    VLOG(1) << __func__ << " advertiser_id: " << +advertiser_id
            << " ,enable: " << enable;

    do_in_bta_thread(
        FROM_HERE,
        Bind(&BleAdvertisingManager::Enable,
             base::Unretained(BleAdvertisingManager::Get()), advertiser_id,
             enable, Bind(&BleAdvertiserInterfaceImpl::MultiAdvEnableCb,
                          base::Unretained(this), cb),
             timeout_s,
             Bind(&BleAdvertiserInterfaceImpl::MultiAdvEnableTimeoutCb,
                  base::Unretained(this), timeout_cb)));
  }
};

BleAdvertiserInterface* btLeAdvertiserInstance = nullptr;

}  // namespace

BleAdvertiserInterface* get_ble_advertiser_instance() {
  if (btLeAdvertiserInstance == nullptr)
    btLeAdvertiserInstance = new BleAdvertiserInterfaceImpl();

  return btLeAdvertiserInstance;
}
