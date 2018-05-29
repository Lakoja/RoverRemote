package de.lakoja.roverremote;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;

public class UdpDataHolder {
    private static final String TAG = UdpDataHolder.class.getName();

    private int timestamp;
    private int normalPacketLength;
    private HashSet<Integer> receivedNumbers = new HashSet<>(30);
    private int maximumPacketCount = 0;

    public UdpDataHolder(int timestamp, int normalPacketLength) {
        this.timestamp = timestamp;
        this.normalPacketLength = normalPacketLength;
    }

    public void add(int packetNumber, int totalPackets, byte[] data, int offset, int length) {
        if (packetNumber < 0) {
            throw new IllegalArgumentException("Packet number must be zero or positive");
        }
        if (totalPackets <= 0) {
            throw new IllegalArgumentException("Total number of packets must be positive");
        }
        if (data.length == 0 || length <= 0) {
            throw new IllegalArgumentException("Data length cannot be zero");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be zero or positive");
        }
        if (offset + length > data.length) {
            throw new ArrayIndexOutOfBoundsException("Cannot address sections with offet+length outside of data length");
        }

        if (maximumPacketCount == 0) {
            maximumPacketCount = totalPackets;
        } else if (maximumPacketCount != totalPackets) {
            throw new IllegalArgumentException("New maximum packet count differs from existing "+totalPackets+" vs "+maximumPacketCount);
        }

        receivedNumbers.add(packetNumber);
    }

    public int[] currentlyMissingPackets() {
        if (maximumPacketCount == 0) {
            return new int[0];
        }

        if (receivedNumbers.size() == maximumPacketCount) {
            return new int[0];
        }

        //Log.w(TAG, "Some missing; first number "+(receivedNumbers.size() > 0 ? receivedNumbers.iterator().next() : "none"));

        ArrayList<Integer> missing = new ArrayList<>(maximumPacketCount);
        for (int i=0; i<maximumPacketCount; i++) {
            if (!receivedNumbers.contains(i)) {
                missing.add(i);
            }
        }

        int[] missingInts = new int[missing.size()];
        for (int i=0; i<missing.size(); i++) {
            missingInts[i] = missing.get(i);
        }

        return missingInts;
    }

    public boolean isDataComplete() {
        return false;
    }

    public byte[] getData() {
        return new byte[0];
    }
}
