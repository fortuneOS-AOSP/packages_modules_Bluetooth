/*
 *  Copyright 1999-2012 Broadcom Corporation
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

/******************************************************************************
 *
 *  This file contains the Bluetooth Manager (BTM) API function external
 *  definitions.
 *
 ******************************************************************************/
#ifndef BTM_API_H
#define BTM_API_H

#include <cstdint>

#include "device/include/esco_parameters.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_api_types.h"
#include "stack/include/btm_status.h"
#include "stack/rnr/remote_name_request.h"
#include "types/bt_transport.h"
#include "types/raw_address.h"

void btm_init();
void btm_free();

/*****************************************************************************
 *  DEVICE CONTROL and COMMON
 ****************************************************************************/

/*****************************************************************************
 *  EXTERNAL FUNCTION DECLARATIONS
 ****************************************************************************/

/*****************************************************************************
 *  DEVICE CONTROL and COMMON FUNCTIONS
 ****************************************************************************/

void BTM_reset_complete();

/*******************************************************************************
 *
 * Function         BTM_IsDeviceUp
 *
 * Description      This function is called to check if the device is up.
 *
 * Returns          true if device is up, else false
 *
 ******************************************************************************/
[[nodiscard]] bool BTM_IsDeviceUp(void);

/*******************************************************************************
 *
 * Function         BTM_SetLocalDeviceName
 *
 * Description      This function is called to set the local device name.
 *
 * Returns          tBTM_STATUS::BTM_CMD_STARTED if successful, otherwise an error
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_SetLocalDeviceName(const char* p_name);

/*******************************************************************************
 *
 * Function         BTM_SetDeviceClass
 *
 * Description      This function is called to set the local device class
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if successful, otherwise an error
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_SetDeviceClass(DEV_CLASS dev_class);

/*******************************************************************************
 *
 * Function         BTM_ReadLocalDeviceName
 *
 * Description      This function is called to read the local device name.
 *
 * Returns          status of the operation
 *                  If success, tBTM_STATUS::BTM_SUCCESS is returned and p_name points stored
 *                              local device name
 *                  If BTM doesn't store local device name, tBTM_STATUS::BTM_NO_RESOURCES is
 *                              is returned and p_name is set to NULL
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_ReadLocalDeviceName(const char** p_name);

/*******************************************************************************
 *
 * Function         BTM_ReadDeviceClass
 *
 * Description      This function is called to read the local device class
 *
 * Returns          the device class
 *
 ******************************************************************************/
[[nodiscard]] DEV_CLASS BTM_ReadDeviceClass(void);

/*******************************************************************************
 *
 * Function         BTM_VendorSpecificCommand
 *
 * Description      Send a vendor specific HCI command to the controller.
 *
 ******************************************************************************/
void BTM_VendorSpecificCommand(uint16_t opcode, uint8_t param_len, uint8_t* p_param_buf,
                               tBTM_VSC_CMPL_CB* p_cb);

/*******************************************************************************
 *
 * Function         BTM_WritePageTimeout
 *
 * Description      Send HCI Wite Page Timeout.
 *
 ******************************************************************************/
void BTM_WritePageTimeout(uint16_t timeout);

/*******************************************************************************
 *
 * Function         BTM_WriteVoiceSettings
 *
 * Description      Send HCI Write Voice Settings command.
 *                  See hcidefs.h for settings bitmask values.
 *
 ******************************************************************************/
void BTM_WriteVoiceSettings(uint16_t settings);

/*******************************************************************************
 *
 * Function         BTM_EnableTestMode
 *
 * Description      Send HCI the enable device under test command.
 *
 *                  Note: Controller can only be taken out of this mode by
 *                      resetting the controller.
 *
 * Returns
 *      tBTM_STATUS::BTM_SUCCESS         Command sent.
 *      tBTM_STATUS::BTM_NO_RESOURCES    If out of resources to send the command.
 *
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_EnableTestMode(void);

/*******************************************************************************
 *
 * Function         BTM_IsRemoteVersionReceived
 *
 * Returns          Returns true if "LE Read remote version info" was already
 *                  received on LE transport for this device.
 *
 ******************************************************************************/
[[nodiscard]] bool BTM_IsRemoteVersionReceived(const RawAddress& remote_bda);

/*******************************************************************************
 *
 * Function         BTM_ReadRemoteVersion
 *
 * Description      This function is called to read a remote device's version
 *
 * Returns          true if data valid, false otherwise
 *
 ******************************************************************************/
[[nodiscard]] bool BTM_ReadRemoteVersion(const RawAddress& addr, uint8_t* lmp_version,
                                         uint16_t* manufacturer, uint16_t* lmp_sub_version);

/*******************************************************************************
 *
 * Function         BTM_ReadRemoteFeatures
 *
 * Description      This function is called to read a remote device's
 *                  supported features mask (features mask located at page 0)
 *
 * Returns          pointer to the remote supported features mask
 *                  The size of device features mask page is
 *                  HCI_FEATURE_BYTES_PER_PAGE bytes.
 *
 ******************************************************************************/
[[nodiscard]] uint8_t* BTM_ReadRemoteFeatures(const RawAddress& addr);

/*******************************************************************************
 *
 * Function         BTM_InqDbRead
 *
 * Description      This function looks through the inquiry database for a match
 *                  based on Bluetooth Device Address. This is the application's
 *                  interface to get the inquiry details of a specific BD
 *                  address.
 *
 * Returns          pointer to entry, or NULL if not found
 *
 ******************************************************************************/
[[nodiscard]] tBTM_INQ_INFO* BTM_InqDbRead(const RawAddress& p_bda);

/*******************************************************************************
 *
 * Function         BTM_InqDbFirst
 *
 * Description      This function looks through the inquiry database for the
 *                  first used entry, and returns that. This is used in
 *                  conjunction with BTM_InqDbNext by applications as a way to
 *                  walk through the inquiry database.
 *
 * Returns          pointer to first in-use entry, or NULL if DB is empty
 *
 ******************************************************************************/
[[nodiscard]] tBTM_INQ_INFO* BTM_InqDbFirst(void);

/*******************************************************************************
 *
 * Function         BTM_InqDbNext
 *
 * Description      This function looks through the inquiry database for the
 *                  next used entry, and returns that.  If the input parameter
 *                  is NULL, the first entry is returned.
 *
 * Returns          pointer to next in-use entry, or NULL if no more found.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_INQ_INFO* BTM_InqDbNext(tBTM_INQ_INFO* p_cur);

/*******************************************************************************
 *
 * Function         BTM_ClearInqDb
 *
 * Description      This function is called to clear out a device or all devices
 *                  from the inquiry database.
 *
 * Parameter        p_bda - (input) BD_ADDR ->  Address of device to clear
 *                                              (NULL clears all entries)
 *
 * Returns          tBTM_STATUS::BTM_BUSY if an inquiry, get remote name, or event filter
 *                          is active, otherwise tBTM_STATUS::BTM_SUCCESS
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_ClearInqDb(const RawAddress* p_bda);

/*****************************************************************************
 *  (e)SCO CHANNEL MANAGEMENT FUNCTIONS
 ****************************************************************************/
/*******************************************************************************
 *
 * Function         BTM_CreateSco
 *
 * Description      This function is called to create an SCO connection. If the
 *                  "is_orig" flag is true, the connection will be originated,
 *                  otherwise BTM will wait for the other side to connect.
 *
 * Returns          tBTM_STATUS::BTM_UNKNOWN_ADDR if the ACL connection is not up
 *                  tBTM_STATUS::BTM_BUSY         if another SCO being set up to
 *                                   the same BD address
 *                  tBTM_STATUS::BTM_NO_RESOURCES if the max SCO limit has been reached
 *                  tBTM_STATUS::BTM_CMD_STARTED  if the connection establishment is started.
 *                                   In this case, "*p_sco_inx" is filled in
 *                                   with the sco index used for the connection.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_CreateSco(const RawAddress* remote_bda, bool is_orig,
                                        uint16_t pkt_types, uint16_t* p_sco_inx,
                                        tBTM_SCO_CB* p_conn_cb, tBTM_SCO_CB* p_disc_cb);

/*******************************************************************************
 *
 * Function         BTM_RemoveSco
 *
 * Description      This function is called to remove a specific SCO connection.
 *
 * Returns          tBTM_STATUS::BTM_CMD_STARTED if successfully initiated, otherwise error
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_RemoveSco(uint16_t sco_inx);

/*******************************************************************************
 *
 * Function         BTM_RemoveScoByBdaddr
 *
 * Description      This function is called to remove a specific SCO connection.
 *                  but using the bluetooth device addess typically used
 *                  for ACL termination.
 *
 * Returns         void
 *
 ******************************************************************************/
void BTM_RemoveScoByBdaddr(const RawAddress& bda);

/*******************************************************************************
 *
 * Function         BTM_ReadScoBdAddr
 *
 * Description      This function is read the remote BD Address for a specific
 *                  SCO connection,
 *
 * Returns          pointer to BD address or NULL if not known
 *
 ******************************************************************************/
[[nodiscard]] const RawAddress* BTM_ReadScoBdAddr(uint16_t sco_inx);

/*******************************************************************************
 *
 * Function         BTM_SetEScoMode
 *
 * Description      This function sets up the negotiated parameters for SCO or
 *                  eSCO, and sets as the default mode used for calls to
 *                  BTM_CreateSco.  It can be called only when there are no
 *                  active (e)SCO links.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if the successful.
 *                  tBTM_STATUS::BTM_BUSY if there are one or more active (e)SCO links.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_SetEScoMode(enh_esco_params_t* p_parms);

/*******************************************************************************
 *
 * Function         BTM_RegForEScoEvts
 *
 * Description      This function registers a SCO event callback with the
 *                  specified instance.  It should be used to received
 *                  connection indication events and change of link parameter
 *                  events.
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS if the successful.
 *                  tBTM_STATUS::BTM_ILLEGAL_VALUE if there is an illegal sco_inx
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_RegForEScoEvts(uint16_t sco_inx, tBTM_ESCO_CBACK* p_esco_cback);

/*******************************************************************************
 *
 * Function         BTM_EScoConnRsp
 *
 * Description      This function is called upon receipt of an (e)SCO connection
 *                  request event (BTM_ESCO_CONN_REQ_EVT) to accept or reject
 *                  the request. Parameters used to negotiate eSCO links.
 *                  If p_parms is NULL, then values set through BTM_SetEScoMode
 *                  are used.
 *                  If the link type of the incoming request is SCO, then only
 *                  the tx_bw, max_latency, content format, and packet_types are
 *                  valid.  The hci_status parameter should be
 *                  ([0x0] to accept, [0x0d..0x0f] to reject)
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void BTM_EScoConnRsp(uint16_t sco_inx, tHCI_STATUS hci_status, enh_esco_params_t* p_parms);

/*******************************************************************************
 *
 * Function         BTM_GetNumScoLinks
 *
 * Description      This function returns the number of active SCO links.
 *
 * Returns          uint8_t
 *
 ******************************************************************************/
[[nodiscard]] uint8_t BTM_GetNumScoLinks(void);

/*******************************************************************************
 *
 * Function         BTM_GetScoDebugDump
 *
 * Description      Get the status of SCO. This function is only used for
 *                  testing and debugging purposes.
 *
 * Returns          Data with SCO related debug dump.
 *
 ******************************************************************************/
[[nodiscard]] tBTM_SCO_DEBUG_DUMP BTM_GetScoDebugDump(void);

/*******************************************************************************
 *
 * Function         BTM_GetPeerDeviceTypeFromFeatures
 *
 * Description      This function is called to retrieve the peer device type
 *                  by referencing the remote features.
 *
 * Parameters:      bd_addr - address of the peer
 *
 * Returns          BT_DEVICE_TYPE_DUMO if both BR/EDR and BLE transports are
 *                  supported by the peer,
 *                  BT_DEVICE_TYPE_BREDR if only BR/EDR transport is supported,
 *                  BT_DEVICE_TYPE_BLE if only BLE transport is supported.
 *
 ******************************************************************************/
[[nodiscard]] tBT_DEVICE_TYPE BTM_GetPeerDeviceTypeFromFeatures(const RawAddress& bd_addr);

/*******************************************************************************
 *
 * Function         BTM_GetHCIConnHandle
 *
 * Description      This function is called to get the handle for an ACL
 *                  connection to a specific remote BD Address.
 *
 * Returns          the handle of the connection, or 0xFFFF if none.
 *
 ******************************************************************************/
[[nodiscard]] uint16_t BTM_GetHCIConnHandle(const RawAddress& remote_bda, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_IsPhy2mSupported
 *
 * Description      This function is called to check PHY 2M support
 *                  from peer device
 * Returns          True when PHY 2M supported false otherwise
 *
 ******************************************************************************/
[[nodiscard]] bool BTM_IsPhy2mSupported(const RawAddress& remote_bda, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_RequestPeerSCA
 *
 * Description      This function is called to request sleep clock accuracy
 *                  from peer device
 *
 ******************************************************************************/
void BTM_RequestPeerSCA(const RawAddress& remote_bda, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_GetPeerSCA
 *
 * Description      This function is called to get peer sleep clock accuracy
 *
 * Returns          SCA or 0xFF if SCA was never previously requested, request
 *                  is not supported by peer device or ACL does not exist
 *
 ******************************************************************************/
[[nodiscard]] uint8_t BTM_GetPeerSCA(const RawAddress& remote_bda, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_WriteEIR
 *
 * Description      This function is called to write EIR data to controller.
 *
 * Parameters       p_buff - allocated HCI command buffer including extended
 *                           inquriry response
 *
 * Returns          tBTM_STATUS::BTM_SUCCESS  - if successful
 *                  tBTM_STATUS::BTM_MODE_UNSUPPORTED - if local device cannot support it
 *
 ******************************************************************************/
[[nodiscard]] tBTM_STATUS BTM_WriteEIR(BT_HDR* p_buff);

/*******************************************************************************
 *
 * Function         BTM_HasEirService
 *
 * Description      This function is called to know if UUID in bit map of UUID.
 *
 * Parameters       p_eir_uuid - bit map of UUID list
 *                  uuid16 - UUID 16-bit
 *
 * Returns          true - if found
 *                  false - if not found
 *
 ******************************************************************************/
[[nodiscard]] bool BTM_HasEirService(const uint32_t* p_eir_uuid, uint16_t uuid16);

/*******************************************************************************
 *
 * Function         BTM_AddEirService
 *
 * Description      This function is called to add a service in the bit map UUID
 *                  list.
 *
 * Parameters       p_eir_uuid - bit mask of UUID list for EIR
 *                  uuid16 - UUID 16-bit
 *
 * Returns          None
 *
 ******************************************************************************/
void BTM_AddEirService(uint32_t* p_eir_uuid, uint16_t uuid16);

/*******************************************************************************
 *
 * Function         BTM_RemoveEirService
 *
 * Description      This function is called to remove a service from the bit map
 *                  UUID list.
 *
 * Parameters       p_eir_uuid - bit mask of UUID list for EIR
 *                  uuid16 - UUID 16-bit
 *
 * Returns          None
 *
 ******************************************************************************/
void BTM_RemoveEirService(uint32_t* p_eir_uuid, uint16_t uuid16);

/*******************************************************************************
 *
 * Function         BTM_GetEirSupportedServices
 *
 * Description      This function is called to get UUID list from bit map UUID
 *                  list.
 *
 * Parameters       p_eir_uuid - bit mask of UUID list for EIR
 *                  p - reference of current pointer of EIR
 *                  max_num_uuid16 - max number of UUID can be written in EIR
 *                  num_uuid16 - number of UUID have been written in EIR
 *
 * Returns          HCI_EIR_MORE_16BITS_UUID_TYPE, if it has more than max
 *                  HCI_EIR_COMPLETE_16BITS_UUID_TYPE, otherwise
 *
 ******************************************************************************/
[[nodiscard]] uint8_t BTM_GetEirSupportedServices(uint32_t* p_eir_uuid, uint8_t** p,
                                                  uint8_t max_num_uuid16, uint8_t* p_num_uuid16);

/*******************************************************************************
 *
 * Function         BTM_GetEirUuidList
 *
 * Description      This function parses EIR and returns UUID list.
 *
 * Parameters       p_eir - EIR
 *                  eirl_len - EIR len
 *                  uuid_size - Uuid::kNumBytes16, Uuid::kNumBytes32,
 *                              Uuid::kNumBytes128
 *                  p_num_uuid - return number of UUID in found list
 *                  p_uuid_list - return UUID 16-bit list
 *                  max_num_uuid - maximum number of UUID to be returned
 *
 * Returns          0 - if not found
 *                  HCI_EIR_COMPLETE_16BITS_UUID_TYPE
 *                  HCI_EIR_MORE_16BITS_UUID_TYPE
 *                  HCI_EIR_COMPLETE_32BITS_UUID_TYPE
 *                  HCI_EIR_MORE_32BITS_UUID_TYPE
 *                  HCI_EIR_COMPLETE_128BITS_UUID_TYPE
 *                  HCI_EIR_MORE_128BITS_UUID_TYPE
 *
 ******************************************************************************/
[[nodiscard]] uint8_t BTM_GetEirUuidList(const uint8_t* p_eir, size_t eir_len, uint8_t uuid_size,
                                         uint8_t* p_num_uuid, uint8_t* p_uuid_list,
                                         uint8_t max_num_uuid);

[[nodiscard]] bool BTM_IsScoActiveByBdaddr(const RawAddress& remote_bda);

/* Read maximum data packet that can be sent over current connection */
[[nodiscard]] uint16_t BTM_GetMaxPacketSize(const RawAddress& addr);

typedef void(BTM_CONSOLIDATION_CB)(const RawAddress& identity_addr, const RawAddress& rpa);
void BTM_SetConsolidationCallback(BTM_CONSOLIDATION_CB* cb);

#endif /* BTM_API_H */
