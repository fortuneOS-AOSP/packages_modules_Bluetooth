/*
 * Copyright (C) 2013 Samsung System LSI
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
package com.android.bluetooth.map;

import static android.telephony.TelephonyManager.PHONE_TYPE_CDMA;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.content.Context;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.content_profiles.ContentProfileErrorReportUtils;
import com.android.bluetooth.util.GsmAlphabet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;

// Next tag value for ContentProfileErrorReportUtils.report(): 10
public class BluetoothMapSmsPdu {

    private static final String TAG = "BluetoothMapSmsPdu";
    private static final int INVALID_VALUE = -1;
    public static final int SMS_TYPE_GSM = 1;
    public static final int SMS_TYPE_CDMA = 2;

    /** from SMS user data header information element identifiers. (see TS 23.040 9.2.3.24) */
    private static final int ELT_ID_NATIONAL_LANGUAGE_SINGLE_SHIFT = 0x24;

    private static final int ELT_ID_NATIONAL_LANGUAGE_LOCKING_SHIFT = 0x25;

    /** Supported message types for CDMA SMS messages (See 3GPP2 C.S0015-B, v2.0, table 4.5.1-1) */
    private static final int MESSAGE_TYPE_DELIVER = 0x01;

    /**
     * We need to handle the SC-address mentioned in errata 4335. Since the definition could be read
     * in three different ways, I have asked the car working group for clarification, and are
     * awaiting confirmation that this clarification will go into the MAP spec: The native format
     * should be <sc_addr><tpdu> where <sc_addr> is <length><ton><1..10 octet of address> coded
     * according to 24.011. The IEI is not to be used, as the fixed order of the data makes a type 4
     * LV information element sufficient. <length> is a single octet which value is the length of
     * the value-field in octets including both the <ton> and the <address>.
     */
    public static class SmsPdu {
        private byte[] mData;
        private byte[] mScAddress = {0};
        // At the moment we do not use the scAddress, hence set the length to 0.
        private int mUserDataMsgOffset = 0;
        private int mEncoding;
        private int mLanguageTable;
        private int mLanguageShiftTable;
        private int mType;

        /* Members used for pdu decoding */
        private int mUserDataSeptetPadding = INVALID_VALUE;
        private int mMsgSeptetCount = 0;

        SmsPdu(byte[] data, int type) {
            this.mData = data;
            this.mEncoding = INVALID_VALUE;
            this.mType = type;
            this.mLanguageTable = INVALID_VALUE;
            this.mLanguageShiftTable = INVALID_VALUE;
            this.mUserDataMsgOffset = gsmSubmitGetTpUdOffset(); // Assume no user data header
        }

        /** Create a pdu instance based on the data generated on this device. */
        SmsPdu(byte[] data, int encoding, int type, int languageTable) {
            this.mData = data;
            this.mEncoding = encoding;
            this.mType = type;
            this.mLanguageTable = languageTable;
        }

        public byte[] getData() {
            return mData;
        }

        public byte[] getScAddress() {
            return mScAddress;
        }

        public void setEncoding(int encoding) {
            this.mEncoding = encoding;
        }

        public int getEncoding() {
            return mEncoding;
        }

        public int getType() {
            return mType;
        }

        public int getUserDataMsgOffset() {
            return mUserDataMsgOffset;
        }

        /** The user data message payload size in bytes - excluding the user data header. */
        public int getUserDataMsgSize() {
            return mData.length - mUserDataMsgOffset;
        }

        public int getLanguageShiftTable() {
            return mLanguageShiftTable;
        }

        public int getLanguageTable() {
            return mLanguageTable;
        }

        public int getUserDataSeptetPadding() {
            return mUserDataSeptetPadding;
        }

        public int getMsgSeptetCount() {
            return mMsgSeptetCount;
        }

        /* PDU parsing/modification functionality */
        private static final byte ORIGINATING_ADDRESS = 0x02;
        private static final byte ORIGINATING_SUB_ADDRESS = 0x03;
        private static final byte DESTINATION_ADDRESS = 0x04;
        private static final byte DESTINATION_SUB_ADDRESS = 0x05;
        private static final byte BEARER_DATA = 0x08;

        /**
         * Find and return the offset to the specified parameter ID
         *
         * @param parameterId The parameter ID to find
         * @return the offset in number of bytes to the parameterID entry in the pdu data. The byte
         *     at the offset contains the parameter ID, the byte following contains the parameter
         *     length, and offset + 2 is the first byte of the parameter data.
         */
        private int cdmaGetParameterOffset(byte parameterId) {
            ByteArrayInputStream pdu = new ByteArrayInputStream(mData);
            int offset = 0;
            boolean found = false;

            try {
                pdu.skip(1); // Skip the message type

                while (pdu.available() > 0) {
                    int currentId = pdu.read();
                    int currentLen = pdu.read();

                    if (currentId == parameterId) {
                        found = true;
                        break;
                    } else {
                        pdu.skip(currentLen);
                        offset += 2 + currentLen;
                    }
                }
                pdu.close();
            } catch (Exception e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.MAP,
                        BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        0);
                Log.e(TAG, "cdmaGetParameterOffset: ", e);
            }

            if (found) {
                return offset;
            } else {
                return 0;
            }
        }

        private static final byte BEARER_DATA_MSG_ID = 0x00;

        private int cdmaGetSubParameterOffset(byte subParameterId) {
            ByteArrayInputStream pdu = new ByteArrayInputStream(mData);
            boolean found = false;
            int offset =
                    cdmaGetParameterOffset(BEARER_DATA)
                            + 2; // Add to offset the BEARER_DATA parameter id and length bytes
            pdu.skip(offset);
            try {

                while (pdu.available() > 0) {
                    int currentId = pdu.read();
                    int currentLen = pdu.read();

                    if (currentId == subParameterId) {
                        found = true;
                        break;
                    } else {
                        pdu.skip(currentLen);
                        offset += 2 + currentLen;
                    }
                }
                pdu.close();
            } catch (Exception e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.MAP,
                        BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        1);
                Log.e(TAG, "cdmaGetParameterOffset: ", e);
            }

            if (found) {
                return offset;
            } else {
                return 0;
            }
        }

        public void cdmaChangeToDeliverPdu(long date) {
            /* Things to change:
             *  - Message Type in bearer data (Not the overall point-to-point type)
             *  - Change address ID from destination to originating (sub addresses are not used)
             *  - A time stamp is not mandatory.
             */
            int offset;
            if (mData == null) {
                throw new IllegalArgumentException("Unable to convert PDU to Deliver type");
            }
            offset = cdmaGetParameterOffset(DESTINATION_ADDRESS);
            if (mData.length < offset) {
                throw new IllegalArgumentException("Unable to convert PDU to Deliver type");
            }
            mData[offset] = ORIGINATING_ADDRESS;

            offset = cdmaGetParameterOffset(DESTINATION_SUB_ADDRESS);
            if (mData.length < offset) {
                throw new IllegalArgumentException("Unable to convert PDU to Deliver type");
            }
            mData[offset] = ORIGINATING_SUB_ADDRESS;

            offset = cdmaGetSubParameterOffset(BEARER_DATA_MSG_ID);

            if (mData.length > (2 + offset)) {
                int tmp =
                        mData[offset + 2]
                                & 0xff; // Skip the subParam ID and length, and read the first byte.
                // Mask out the type
                tmp &= 0x0f;
                // Set the new type
                tmp |= ((MESSAGE_TYPE_DELIVER << 4) & 0xf0);
                // Store the result
                mData[offset + 2] = (byte) tmp;

            } else {
                throw new IllegalArgumentException("Unable to convert PDU to Deliver type");
            }
            /* TODO: Do we need to change anything in the user data? Not sure if the user
            data is
             *        just encoded using GSM encoding, or it is an actual GSM submit PDU
             *        embedded
             *        in the user data?
             */
        }

        private static final byte TP_MIT_DELIVER = 0x00; // bit 0 and 1
        private static final byte TP_MMS_NO_MORE = 0x04; // bit 2
        private static final byte TP_RP_NO_REPLY_PATH = 0x00; // bit 7
        private static final byte TP_UDHI_MASK = 0x40; // bit 6
        private static final byte TP_SRI_NO_REPORT = 0x00; // bit 5

        private int gsmSubmitGetTpPidOffset() {
            /* calculate the offset to TP_PID.
             * The TP-DA has variable length, and the length excludes the 2 byte length and type
             * headers.
             * The TP-DA is two bytes within the PDU */
            // data[2] is the number of semi-octets in the phone number (ceil result)
            int offset = 2 + ((mData[2] + 1) & 0xff) / 2 + 2;
            // max length of TP_DA is 12 bytes + two byte offset.
            if ((offset > mData.length) || (offset > (2 + 12))) {
                throw new IllegalArgumentException(
                        "wrongly formatted gsm submit PDU. offset = " + offset);
            }
            return offset;
        }

        public int gsmSubmitGetTpDcs() {
            return mData[gsmSubmitGetTpDcsOffset()] & 0xff;
        }

        public boolean gsmSubmitHasUserDataHeader() {
            return ((mData[0] & 0xff) & TP_UDHI_MASK) == TP_UDHI_MASK;
        }

        private int gsmSubmitGetTpDcsOffset() {
            return gsmSubmitGetTpPidOffset() + 1;
        }

        private int gsmSubmitGetTpUdlOffset() {
            switch (((mData[0] & 0xff) & (0x08 | 0x04)) >> 2) {
                case 0: // Not TP-VP present
                    return gsmSubmitGetTpPidOffset() + 2;
                case 1: // TP-VP relative format
                    return gsmSubmitGetTpPidOffset() + 2 + 1;
                case 2: // TP-VP enhanced format
                case 3: // TP-VP absolute format
                    break;
            }
            return gsmSubmitGetTpPidOffset() + 2 + 7;
        }

        private int gsmSubmitGetTpUdOffset() {
            return gsmSubmitGetTpUdlOffset() + 1;
        }

        public void gsmDecodeUserDataHeader() {
            ByteArrayInputStream pdu = new ByteArrayInputStream(mData);

            pdu.skip(gsmSubmitGetTpUdlOffset());
            int userDataLength = pdu.read();
            if (gsmSubmitHasUserDataHeader()) {
                int userDataHeaderLength = pdu.read();

                // This part is only needed to extract the language info, hence only needed for 7
                // bit encoding
                if (mEncoding == SmsConstants.ENCODING_7BIT) {
                    byte[] udh = new byte[userDataHeaderLength];
                    try {
                        pdu.read(udh);
                    } catch (IOException e) {
                        ContentProfileErrorReportUtils.report(
                                BluetoothProfile.MAP,
                                BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                                BluetoothStatsLog
                                        .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                                2);
                        Log.w(TAG, "unable to read userDataHeader", e);
                    }
                    int[] tableValue = getTableFromByteArray(udh);
                    mLanguageTable = tableValue[0];
                    mLanguageShiftTable = tableValue[1];

                    int headerBits = (userDataHeaderLength + 1) * 8;
                    int headerSeptets = headerBits / 7;
                    headerSeptets += (headerBits % 7) > 0 ? 1 : 0;
                    mUserDataSeptetPadding = (headerSeptets * 7) - headerBits;
                    mMsgSeptetCount = userDataLength - headerSeptets;
                }
                mUserDataMsgOffset =
                        gsmSubmitGetTpUdOffset()
                                + userDataHeaderLength
                                + 1; // Add the byte containing the length
            } else {
                mUserDataSeptetPadding = 0;
                mMsgSeptetCount = userDataLength;
                mUserDataMsgOffset = gsmSubmitGetTpUdOffset();
            }
            Log.v(TAG, "encoding:" + mEncoding);
            Log.v(TAG, "msgSeptetCount:" + mMsgSeptetCount);
            Log.v(TAG, "userDataSeptetPadding:" + mUserDataSeptetPadding);
            Log.v(TAG, "languageShiftTable:" + mLanguageShiftTable);
            Log.v(TAG, "languageTable:" + mLanguageTable);
            Log.v(TAG, "userDataMsgOffset:" + mUserDataMsgOffset);
        }

        @SuppressWarnings("JavaUtilDate") // TODO: b/365629730 -- prefer Instant or LocalDate
        private void gsmWriteDate(ByteArrayOutputStream header, long time) {
            SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss");
            Date date = new Date(time);
            String timeStr = format.format(date); // Format to YYMMDDTHHMMSS UTC time
            Log.v(TAG, "Generated time string: " + timeStr);
            byte[] timeChars = timeStr.getBytes(StandardCharsets.US_ASCII);

            for (int i = 0, n = timeStr.length(); i < n; i += 2) {
                header.write(
                        (timeChars[i + 1] - 0x30) << 4
                                | (timeChars[i] - 0x30)); // Offset from ascii char to decimal value
            }

            Calendar cal = Calendar.getInstance();
            int offset =
                    (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET))
                            / (15 * 60 * 1000); /* offset in quarters of an hour */
            String offsetString;
            if (offset < 0) {
                offsetString = String.format("%1$02d", -(offset));
                char[] offsetChars = offsetString.toCharArray();
                header.write((offsetChars[1] - 0x30) << 4 | 0x40 | (offsetChars[0] - 0x30));
            } else {
                offsetString = String.format("%1$02d", offset);
                char[] offsetChars = offsetString.toCharArray();
                header.write((offsetChars[1] - 0x30) << 4 | (offsetChars[0] - 0x30));
            }
        }

        /*        private void gsmSubmitExtractUserData() {
            int userDataLength = data[gsmSubmitGetTpUdlOffset()];
            userData = new byte[userDataLength];
            System.arraycopy(userData, 0, data, gsmSubmitGetTpUdOffset(), userDataLength);

        }*/

        /**
         * Change the GSM Submit Pdu data in this object to a deliver PDU: - Build the new header
         * with deliver PDU type, originator and time stamp. - Extract encoding details from the
         * submit PDU - Extract user data length and user data from the submitPdu - Build the new
         * PDU
         *
         * @param date the time stamp to include (The value is the number of milliseconds since Jan.
         *     1, 1970 GMT.)
         * @param originator the phone number to include in the deliver PDU header. Any undesired
         *     characters, such as '-' will be striped from this string.
         */
        public void gsmChangeToDeliverPdu(long date, String originator) {
            ByteArrayOutputStream newPdu =
                    new ByteArrayOutputStream(22); // 22 is the max length of the deliver pdu header
            byte[] encodedAddress;
            int userDataLength = 0;
            try {
                newPdu.write(
                        TP_MIT_DELIVER
                                | TP_MMS_NO_MORE
                                | TP_RP_NO_REPLY_PATH
                                | TP_SRI_NO_REPORT
                                | ((mData[0] & 0xff) & TP_UDHI_MASK));
                encodedAddress =
                        PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength(originator);
                if (encodedAddress != null) {
                    int padding =
                            (encodedAddress[encodedAddress.length - 1] & 0xf0) == 0xf0 ? 1 : 0;
                    encodedAddress[0] =
                            (byte)
                                    ((encodedAddress[0] - 1) * 2
                                            - padding); // Convert from octet length to semi octet
                    // length
                    // Insert originator address into the header - this includes the length
                    newPdu.write(encodedAddress);
                } else {
                    newPdu.write(0); /* zero length */
                    newPdu.write(0x81); /* International type */
                }

                newPdu.write(mData[gsmSubmitGetTpPidOffset()]);
                newPdu.write(mData[gsmSubmitGetTpDcsOffset()]);
                // Generate service center time stamp
                gsmWriteDate(newPdu, date);
                userDataLength = (mData[gsmSubmitGetTpUdlOffset()] & 0xff);
                newPdu.write(userDataLength);
                // Copy the pdu user data - keep in mind that the userDataLength is not the
                // length in bytes for 7-bit encoding.
                newPdu.write(
                        mData, gsmSubmitGetTpUdOffset(), mData.length - gsmSubmitGetTpUdOffset());
            } catch (IOException e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.MAP,
                        BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        3);
                Log.e(TAG, "", e);
                throw new IllegalArgumentException("Failed to change type to deliver PDU.");
            }
            mData = newPdu.toByteArray();
        }

        /* SMS encoding to bmessage strings */

        /** get the encoding type as a bMessage string */
        public String getEncodingString() {
            if (mType == SMS_TYPE_GSM) {
                switch (mEncoding) {
                    case SmsMessage.ENCODING_7BIT:
                        if (mLanguageTable == 0) {
                            return "G-7BIT";
                        } else {
                            return "G-7BITEXT";
                        }
                    case SmsMessage.ENCODING_8BIT:
                        return "G-8BIT";
                    case SmsMessage.ENCODING_16BIT:
                        return "G-16BIT";
                    case SmsMessage.ENCODING_UNKNOWN:
                    default:
                        return "";
                }
            } else /* SMS_TYPE_CDMA */ {
                switch (mEncoding) {
                    case SmsMessage.ENCODING_7BIT:
                        return "C-7ASCII";
                    case SmsMessage.ENCODING_8BIT:
                        return "C-8BIT";
                    case SmsMessage.ENCODING_16BIT:
                        return "C-UNICODE";
                    case SmsMessage.ENCODING_KSC5601:
                        return "C-KOREAN";
                    case SmsMessage.ENCODING_UNKNOWN:
                    default:
                        return "";
                }
            }
        }
    }

    private static int sConcatenatedRef = new Random().nextInt(256);

    protected static int getNextConcatenatedRef() {
        sConcatenatedRef += 1;
        return sConcatenatedRef;
    }

    public static List<SmsPdu> getSubmitPdus(Context context, String messageText, String address) {
        /* Use the generic GSM/CDMA SMS Message functionality within Android to generate the
         * SMS PDU's as once generated to send the SMS message.
         */

        int activePhone = context.getSystemService(TelephonyManager.class).getCurrentPhoneType();
        int phoneType;
        int[] ted = SmsMessage.calculateLength((CharSequence) messageText, false);

        SmsPdu newPdu;
        String destinationAddress;
        int msgCount = ted[0];
        int encoding;
        int languageTable;
        int languageShiftTable;
        int refNumber = getNextConcatenatedRef() & 0x00FF;
        SmsManager smsMng = SmsManager.getDefault();
        List<String> smsFragments = smsMng.divideMessage(messageText);
        List<SmsPdu> pdus = new ArrayList<>(msgCount);
        byte[] data;

        // Default to GSM, as this code should not be used, if we neither have CDMA not GSM.
        phoneType = (activePhone == PHONE_TYPE_CDMA) ? SMS_TYPE_CDMA : SMS_TYPE_GSM;
        encoding = ted[3];
        languageTable = ted[4];
        languageShiftTable = ted[5];
        destinationAddress = PhoneNumberUtils.stripSeparators(address);
        if (destinationAddress == null || destinationAddress.length() < 2) {
            destinationAddress =
                    "12"; // Ensure we add a number at least 2 digits as specified in the GSM spec.
        }

        if (msgCount == 1) {
            data =
                    SmsMessage.getSubmitPdu(null, destinationAddress, smsFragments.get(0), false)
                            .encodedMessage;
            newPdu = new SmsPdu(data, encoding, phoneType, languageTable);
            pdus.add(newPdu);
        } else {
            /* This code is a reduced copy of the actual code used in the Android SMS sub system,
             * hence the comments have been left untouched. */
            for (int i = 0; i < msgCount; i++) {
                data =
                        SmsMessage.getSubmitPduEncodedMessage(
                                phoneType == SMS_TYPE_GSM,
                                destinationAddress,
                                smsFragments.get(i),
                                encoding,
                                languageTable,
                                languageShiftTable,
                                refNumber,
                                i + 1,
                                msgCount);
                newPdu = new SmsPdu(data, encoding, phoneType, languageTable);
                pdus.add(newPdu);
            }
        }

        return pdus;
    }

    /**
     * Generate a list of deliver PDUs. The messageText and address parameters must be different
     * from null, for CDMA the date can be omitted (and will be ignored if supplied)
     *
     * @param messageText The text to include.
     * @param address The originator address.
     * @param date The delivery time stamp.
     */
    public static List<SmsPdu> getDeliverPdus(
            Context context, String messageText, String address, long date) {
        List<SmsPdu> deliverPdus = getSubmitPdus(context, messageText, address);

        /*
         * For CDMA the only difference between deliver and submit pdus are the messageType,
         * which is set in encodeMessageId, (the higher 4 bits of the 1st byte
         * of the Message identification sub parameter data.) and the address type.
         *
         * For GSM, a larger part of the header needs to be generated.
         */
        for (SmsPdu currentPdu : deliverPdus) {
            if (currentPdu.getType() == SMS_TYPE_CDMA) {
                currentPdu.cdmaChangeToDeliverPdu(date);
            } else {
                /* SMS_TYPE_GSM */
                currentPdu.gsmChangeToDeliverPdu(date, address);
            }
        }

        return deliverPdus;
    }

    /**
     * The decoding only supports decoding the actual textual content of the PDU received from the
     * MAP client. (As the Android system has no interface to send pre encoded PDUs) The destination
     * address must be extracted from the bmessage vCard(s).
     */
    public static String decodePdu(byte[] data, int type) {
        String ret = "";
        if (type == SMS_TYPE_CDMA) {
            /* This is able to handle both submit and deliver PDUs */
            SmsMessage smsMessage = SmsMessage.createFromNativeSmsSubmitPdu(data, true);
            if (smsMessage != null) {
                ret = smsMessage.getMessageBody();
            }
        } else {
            /* For GSM, there is no submit pdu decoder, and most parser utils are private, and
            only minded for submit pdus */
            ret = gsmParseSubmitPdu(data);
        }
        return ret;
    }

    /* At the moment we do not support using a SC-address. Use this function to strip off
     * the SC-address before parsing it to the SmsPdu. (this was added in errata 4335)
     */
    private static byte[] gsmStripOffScAddress(byte[] data) {
        /* The format of a native GSM SMS is: <sc-address><pdu> where sc-address is:
         * <length-byte><type-byte><number-bytes> */
        int addressLength = data[0] & 0xff; // Treat the byte value as an unsigned value
        // We could verify that the address-length is no longer than 11 bytes
        if (addressLength >= data.length) {
            throw new IllegalArgumentException(
                    "Length of address exeeds the length of the PDU data.");
        }
        int pduLength = data.length - (1 + addressLength);
        byte[] newData = new byte[pduLength];
        System.arraycopy(data, 1 + addressLength, newData, 0, pduLength);
        return newData;
    }

    private static String gsmParseSubmitPdu(byte[] data) {
        /* Things to do:
         *  - extract hasUsrData bit
         *  - extract TP-DCS -> Character set, compressed etc.
         *  - extract user data header to get the language properties
         *  - extract user data
         *  - decode the string */
        // Strip off the SC-address before parsing
        SmsPdu pdu = new SmsPdu(gsmStripOffScAddress(data), SMS_TYPE_GSM);
        boolean userDataCompressed = false;
        int dataCodingScheme = pdu.gsmSubmitGetTpDcs();
        int encodingType = SmsConstants.ENCODING_UNKNOWN;
        String messageBody = null;

        // Look up the data encoding scheme
        if ((dataCodingScheme & 0x80) == 0) {
            // Bits 7..4 == 0xxx
            userDataCompressed = (0 != (dataCodingScheme & 0x20));

            if (userDataCompressed) {
                Log.w(
                        TAG,
                        "4 - Unsupported SMS data coding scheme "
                                + "(compression) "
                                + (dataCodingScheme & 0xff));
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.MAP,
                        BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__LOG_WARN,
                        4);
            } else {
                switch ((dataCodingScheme >> 2) & 0x3) {
                    case 0: // GSM 7 bit default alphabet
                        encodingType = SmsConstants.ENCODING_7BIT;
                        break;

                    case 2: // UCS 2 (16bit)
                        encodingType = SmsConstants.ENCODING_16BIT;
                        break;

                    case 1: // 8 bit data
                    case 3: // reserved
                        Log.w(
                                TAG,
                                "1 - Unsupported SMS data coding scheme "
                                        + (dataCodingScheme & 0xff));
                        ContentProfileErrorReportUtils.report(
                                BluetoothProfile.MAP,
                                BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                                BluetoothStatsLog
                                        .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__LOG_WARN,
                                5);
                        encodingType = SmsConstants.ENCODING_8BIT;
                        break;
                }
            }
        } else if ((dataCodingScheme & 0xf0) == 0xf0) {
            userDataCompressed = false;

            if (0 == (dataCodingScheme & 0x04)) {
                // GSM 7 bit default alphabet
                encodingType = SmsConstants.ENCODING_7BIT;
            } else {
                // 8 bit data
                encodingType = SmsConstants.ENCODING_8BIT;
            }
        } else if ((dataCodingScheme & 0xF0) == 0xC0
                || (dataCodingScheme & 0xF0) == 0xD0
                || (dataCodingScheme & 0xF0) == 0xE0) {
            // 3GPP TS 23.038 V7.0.0 (2006-03) section 4

            // 0xC0 == 7 bit, don't store
            // 0xD0 == 7 bit, store
            // 0xE0 == UCS-2, store

            if ((dataCodingScheme & 0xF0) == 0xE0) {
                encodingType = SmsConstants.ENCODING_16BIT;
            } else {
                encodingType = SmsConstants.ENCODING_7BIT;
            }

            userDataCompressed = false;

            // bit 0x04 reserved
        } else if ((dataCodingScheme & 0xC0) == 0x80) {
            // 3GPP TS 23.038 V7.0.0 (2006-03) section 4
            // 0x80..0xBF == Reserved coding groups
            if (dataCodingScheme == 0x84) {
                // This value used for KSC5601 by carriers in Korea.
                encodingType = SmsConstants.ENCODING_KSC5601;
            } else {
                Log.w(TAG, "5 - Unsupported SMS data coding scheme " + (dataCodingScheme & 0xff));
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.MAP,
                        BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__LOG_WARN,
                        6);
            }
        } else {
            Log.w(TAG, "3 - Unsupported SMS data coding scheme " + (dataCodingScheme & 0xff));
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__LOG_WARN,
                    7);
        }

        pdu.setEncoding(encodingType);
        pdu.gsmDecodeUserDataHeader();

        try {
            switch (encodingType) {
                case SmsConstants.ENCODING_UNKNOWN:
                case SmsConstants.ENCODING_8BIT:
                    Log.w(TAG, "Unknown encoding type: " + encodingType);
                    ContentProfileErrorReportUtils.report(
                            BluetoothProfile.MAP,
                            BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                            BluetoothStatsLog
                                    .BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__LOG_WARN,
                            8);
                    messageBody = null;
                    break;

                case SmsConstants.ENCODING_7BIT:
                    messageBody =
                            GsmAlphabet.gsm7BitPackedToString(
                                    pdu.getData(),
                                    pdu.getUserDataMsgOffset(),
                                    pdu.getMsgSeptetCount(),
                                    pdu.getUserDataSeptetPadding(),
                                    pdu.getLanguageTable(),
                                    pdu.getLanguageShiftTable());
                    Log.i(TAG, "Decoded as 7BIT: " + messageBody);

                    break;

                case SmsConstants.ENCODING_16BIT:
                    messageBody =
                            new String(
                                    pdu.getData(),
                                    pdu.getUserDataMsgOffset(),
                                    pdu.getUserDataMsgSize(),
                                    "utf-16");
                    Log.i(TAG, "Decoded as 16BIT: " + messageBody);
                    break;

                case SmsConstants.ENCODING_KSC5601:
                    messageBody =
                            new String(
                                    pdu.getData(),
                                    pdu.getUserDataMsgOffset(),
                                    pdu.getUserDataMsgSize(),
                                    "KSC5601");
                    Log.i(TAG, "Decoded as KSC5601: " + messageBody);
                    break;
            }
        } catch (UnsupportedEncodingException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_SMS_PDU,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    9);
            Log.e(TAG, "Unsupported encoding type???", e); // This should never happen.
            return null;
        }

        return messageBody;
    }

    private static int[] getTableFromByteArray(byte[] data) {
        ByteArrayInputStream inStream = new ByteArrayInputStream(data);
        /* tableValue[0]: languageTable tableValue[1]: languageShiftTable */
        int[] tableValue = new int[2];
        while (inStream.available() > 0) {
            int id = inStream.read();
            int length = inStream.read();
            switch (id) {
                case ELT_ID_NATIONAL_LANGUAGE_SINGLE_SHIFT:
                    tableValue[1] = inStream.read();
                    break;
                case ELT_ID_NATIONAL_LANGUAGE_LOCKING_SHIFT:
                    tableValue[0] = inStream.read();
                    break;
                default:
                    inStream.skip(length);
            }
        }
        return tableValue;
    }

    private static class SmsConstants {
        /** User data text encoding code unit size */
        public static final int ENCODING_UNKNOWN = 0;

        public static final int ENCODING_7BIT = 1;
        public static final int ENCODING_8BIT = 2;
        public static final int ENCODING_16BIT = 3;

        /** This value is not defined in global standard. Only in Korea, this is used. */
        public static final int ENCODING_KSC5601 = 4;
    }
}
