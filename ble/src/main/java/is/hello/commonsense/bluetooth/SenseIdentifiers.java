package is.hello.commonsense.bluetooth;

import java.util.UUID;

import is.hello.buruberi.bluetooth.stacks.util.AdvertisingData;
import is.hello.buruberi.bluetooth.stacks.util.Bytes;

public class SenseIdentifiers {
    public static final String ADVERTISEMENT_SERVICE_128_BIT = "23D1BCEA5F782315DEEF1212E1FE0000";
    public static final String ADVERTISEMENT_SERVICE_16_BIT = "E1FE";

    public static final UUID SERVICE = UUID.fromString("0000FEE1-1212-EFDE-1523-785FEABCD123");

    public static final UUID CHARACTERISTIC_PROTOBUF_COMMAND = UUID.fromString("0000BEEB-0000-1000-8000-00805F9B34FB");
    public static final UUID CHARACTERISTIC_PROTOBUF_COMMAND_RESPONSE = UUID.fromString("0000B00B-0000-1000-8000-00805F9B34FB");

    public static final UUID DESCRIPTOR_CHARACTERISTIC_COMMAND_RESPONSE_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    /**
     * Sense 1.5 (aka SenseWithVoice) returns a HashMap called {@link AdvertisingData#records}.
     * At the time of writing this on 10/24/16, the size of records is 6. Each value is a List of
     * bytes aka List<byte[]>.
     *
     * Sense 1.0's record size is only 5. Because we're unsure if these sizes will ever change
     * we need to look at the individual bytes to determine if the Sense is 1.5 or not.
     *
     * Sense 1.5 will contain a List of bytes where the first 3 elements are
     * [0]: 0xEA hex or -22 decimal.
     * [1]: 0x3 hex or 3 decimal.
     * [2]: 0x22 hex or 34 decimal.
     *
     * Use the following field with {@link #BYTES_COMPANY_BLE_ID_2} and
     * {@link #BYTES_SENSE_WITH_VOICE_ID} to check each index position and determine if the Sense is
     * 1.5.
     *
     * Sense 1.0 may eventually or already contain these three indexes too. But it will have a
     * different value for {@link #BYTES_SENSE_WITH_VOICE_ID}
     */
    public static final int BYTES_COMPANY_BLE_ID_1= 0xEA;
    public static final byte BYTES_COMPANY_BLE_ID_2= 0x3;
    public static final byte BYTES_SENSE_WITH_VOICE_ID = 0x22;

    private static byte[] getAdvertisementCompanyBleBytes(final byte lastByte){
        return new byte[]{(byte) BYTES_COMPANY_BLE_ID_1, BYTES_COMPANY_BLE_ID_2, lastByte};
    }

    private static String getAdvertisementCompanyBleString(final byte lastByte){
        return Bytes.toString(getAdvertisementCompanyBleBytes(lastByte));
    }

    public static final String ADVERTISEMENT_SENSE_WITH_VOICE_ID = getAdvertisementCompanyBleString(BYTES_SENSE_WITH_VOICE_ID);

    public static final byte[] ADVERTISEMENT_SENSE_WITH_VOICE_ID_BYTES_PREFIX = getAdvertisementCompanyBleBytes(BYTES_SENSE_WITH_VOICE_ID);

    /**
     * These are the first three values of the mac address of for every 1.5 Sense.
     */
    public static final String SENSE_WITH_VOICE_MAC_ADDRESS_PREFIX = "5c:6b:4f";

    /**
     * Minimum expected size of byte[] returned for {@link AdvertisingData#TYPE_MANUFACTURER_SPECIFIC_DATA}
     * for {@link is.hello.commonsense.bluetooth.model.SenseHardwareVersion#SENSE_WITH_VOICE}
     */
    public static final int ADVERTISEMENT_COMPANY_BLE_ID_RECORD_SENSE_WITH_VOICE_BYTE_SIZE = 6;
}
