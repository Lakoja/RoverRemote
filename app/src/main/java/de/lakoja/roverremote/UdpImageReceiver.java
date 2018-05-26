package de.lakoja.roverremote;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.DecimalFormat;

public class UdpImageReceiver extends Thread {
    private static final String TAG = UdpImageReceiver.class.getName();

    private long lastStatisticsOutMillis = 0;
    private int port;
    private InetAddress returnServerAddress;
    private boolean active = true;

    public UdpImageReceiver(int port, InetAddress returnServerAddress) {
        this.port = port;
        this.returnServerAddress = returnServerAddress;
    }

    public void stopActive() {
        active = false;
    }

    @Override
    public void run() {
        DatagramSocket udpSocket = null;
        try {
            udpSocket = new DatagramSocket(port);
            udpSocket.setReceiveBufferSize(30000);
        } catch (SocketException exc) {
            Log.e(TAG, "Cannot create UDP socket " + exc.getMessage());
            return;
        }

        int receivedPackets = 0;

        Log.i(TAG, "Opened UDP receiver with "+returnServerAddress);

        DatagramPacket packet = new DatagramPacket(new byte[1000], 1000);
        DatagramPacket packet2 = new DatagramPacket(new byte[1000], 1000, returnServerAddress, port);
        int minIndex = Integer.MAX_VALUE;
        int maxIndex = Integer.MIN_VALUE;
        int lostIndex1 = 0;
        int lostIndex2 = 0;
        while (active) {
            try {
                udpSocket.receive(packet);
            } catch (IOException exc) {
                Log.e(TAG, "Cannot receive UDP packet " + exc.getMessage());
                break;
            }

            receivedPackets++;
            //Log.i(TAG, "Got "+packet.getLength()+": "+new String(packet.getData(), 0, packet.getLength()));

            if (packet.getLength() > 10000) {
                Log.e(TAG, "Infernal packet length received " + packet.getLength());
                break;
            }

            String data = new String(packet.getData(), 0, packet.getLength());

            if (packet.getLength() < 5 || !data.startsWith("ok: ")) {
                Log.e(TAG, "Received bogus packet "+data+ " with length "+packet.getLength());
                continue;
            }

            int x = 0;
            try {
                x = Integer.parseInt(data.substring(4));
            } catch (NumberFormatException exc) {
                Log.e(TAG, "Cannot parse packet number " + data);
                continue;
            }

            if (x < 1) {
                Log.e(TAG, "Received bogus packet value "+x+" from "+data);
                continue;
            }

            if (maxIndex > 0 && x > maxIndex + 1) {
                int range = (x - 1) - (maxIndex + 1);

                if (lostIndex1 == 0 && range <= 1) {
                    lostIndex1 = (maxIndex + 1);
                    String lostRequest = "mi " + lostIndex1;
                    if (range == 1) {
                        lostIndex2 = (x - 1);
                        lostRequest += "," + lostIndex2;
                    }
                    packet2.setData(lostRequest.getBytes()); // this crushes the existing data to length
                    //Log.i(TAG, "Sent reply");
                    try {
                        udpSocket.send(packet2);
                        Log.i(TAG, "Rerequesting " + lostRequest);
                    } catch (IOException exc) {
                        Log.e(TAG, "Problem during sending (rerequest) "+exc.getMessage());
                    }
                } else if (range <= 1) {
                    Log.w(TAG, "Lost again " + (maxIndex + 1) + "+" + range);
                    lostIndex1 = 0;
                    lostIndex2 = 0;
                } else {
                    Log.w(TAG, "Lost long " + (maxIndex + 1) + "+" + range);
                }
                //Log.w(TAG, "Lost "+(maxIndex + 1) + (range > 0 ? "-"+(x-1) : ""));
            }

            // TODO enqueuing and dequeuing of lost indices is broken sometimes

            if (lostIndex1 > 0 && x == lostIndex1) {
                Log.w(TAG, "Got again " + x);
                lostIndex1 = 0;
            } else if (lostIndex2 > 0 && x == lostIndex2) {
                Log.w(TAG, "Got again " + x);
                lostIndex2 = 0;
            } else if (x < maxIndex) {
                Log.w(TAG, "Out of sequence " + x);
            }

            minIndex = Math.min(minIndex, x);
            maxIndex = Math.max(maxIndex, x);

            if (receivedPackets < 3) {
                Log.i(TAG, "Start values "+minIndex+" - "+maxIndex);
            }

            long now = System.currentTimeMillis();
            if (now - lastStatisticsOutMillis > 500) {
                int maxCount = maxIndex - minIndex + 1;
                double recPerc = (receivedPackets/(double)maxCount) * 100;

                Log.i(TAG, "Received " + receivedPackets + " packets. Of "+maxCount+" "+(new DecimalFormat("#.##").format(recPerc))+"% "+minIndex+"-"+maxIndex);

                lastStatisticsOutMillis = now;
            }

            try { Thread.sleep(1); } catch (InterruptedException exc) { }
        }

        Log.w(TAG, "Udp receiver exited");
        udpSocket.close();
    }
}
