package de.lakoja.roverremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.SparseArray;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.DecimalFormat;

public class UdpImageReceiver extends Thread {
    private static final String TAG = UdpImageReceiver.class.getName();
    private static final String IMAGE_PACKET_HEADER = "RI";
    private static final String REREQUEST_PACKET_HEADER = "MN";
    private static final int UDP_PACKET_DATA_LENGTH = 512;

    private long lastStatisticsOutMillis = 0;
    private int port;
    private InetAddress returnServerAddress;
    private boolean active = true;
    private ImageListener imageListener;
    private SparseArray<UdpDataHolder> multipleImageData = new SparseArray<>(11);

    public UdpImageReceiver(int port, InetAddress returnServerAddress) {
        this.port = port;
        this.returnServerAddress = returnServerAddress;
    }

    public void setImageListener(ImageListener imageListener) {
        this.imageListener = imageListener;
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
        int problemFreeImages = 0;
        int problematicImages = 0;
        int shouldHaveReceivedPackets = 0;

        Log.i(TAG, "Opened UDP receiver with "+returnServerAddress);

        DatagramPacket packet = new DatagramPacket(new byte[1000], 1000);
        DatagramPacket returnPacket = new DatagramPacket(new byte[500], 500, returnServerAddress, port);

        // packet header + packet number (for image) + of total packets (for image) + timestamp
        int headerLength = 2 + 2 + 2 + 4;

        int minimumLength = headerLength + 1;
        int maximumLength = headerLength + 512;

        int lastPacketNumber = -1;
        int highestLastTimestamp = -1;

        while (active) {
            try {
                udpSocket.receive(packet);
            } catch (IOException exc) {
                Log.e(TAG, "Cannot receive UDP packet " + exc.getMessage());
                break;
            }

            receivedPackets++;

            if (packet.getLength() < 2) {
                Log.e(TAG, "Packet really too short " + packet.getLength());
                break;
            }

            if (packet.getLength() > 10000) {
                Log.e(TAG, "Infernal packet length received " + packet.getLength());
                break;
            }

            byte[] data = packet.getData();
            String packetHeader = new String(data, 0, 2);

            if (packet.getLength() < minimumLength || packet.getLength() > maximumLength || !packetHeader.equals(IMAGE_PACKET_HEADER)) {
                Log.e(TAG, "Received bogus packet "+packetHeader+ " with length "+packet.getLength());
                continue;
            }

            int timestamp = (data[2] << 24) & 0xff000000 | (data[3] << 16) & 0xff0000 | (data[4] << 8) & 0xff00 | data[5] & 0xff;

            if (timestamp < 0) {
                Log.e(TAG, "Received bogus packet timestamp "+timestamp);
                continue;
            }

            int packetNumber = (data[6] << 8) & 0xff00 | data[7] & 0xff;

            if (packetNumber < 0) {
                Log.e(TAG, "Received bogus packet number "+packetNumber);
                continue;
            }

            int packetsForThisImage = (data[8] << 8) & 0xff00 | data[9] & 0xff;

            if (packetsForThisImage < 1) {
                Log.e(TAG, "Received bogus packet total "+packetsForThisImage);
                continue;
            }

            if (receivedPackets % 100 == 0) {
                Log.i(TAG, "Got 100th packet "+timestamp+" "+packetNumber+"/"+packetsForThisImage+ " of "+shouldHaveReceivedPackets);
            }

            //Log.i(TAG, "Got packet "+timestamp+" "+packetNumber+"/"+packetsForThisImage);

            UdpDataHolder thisImageDataHolder = multipleImageData.get(timestamp);
            boolean isRepairData = false;

            if (thisImageDataHolder == null) {
                if (timestamp < highestLastTimestamp) {
                    Log.w(TAG, "Discarding data for old image " + timestamp + " highest " + highestLastTimestamp);
                } else {
                    thisImageDataHolder = new UdpDataHolder(timestamp, UDP_PACKET_DATA_LENGTH);
                    multipleImageData.put(timestamp, thisImageDataHolder);
                }
            } else {
                if (timestamp < highestLastTimestamp) {
                    isRepairData = true;
                }
            }

            if (timestamp > highestLastTimestamp) {
                // A new image starts

                shouldHaveReceivedPackets += packetsForThisImage;

                if (highestLastTimestamp != -1) {
                    // TODO check if there are whole images missing?

                    // TODO check if that image is initialized at all?
                    UdpDataHolder lastImageDataHolder = multipleImageData.get(highestLastTimestamp);

                    multipleImageData.clear();

                    // TODO beautify
                    multipleImageData.put(timestamp, thisImageDataHolder);

                    // Only keep current and last image and that only if necessary

                    if (lastImageDataHolder != null) {
                        int[] lastPacketsMissing = lastImageDataHolder.currentlyMissingPackets();

                        if (lastPacketsMissing.length > 0) {
                            problematicImages++;

                            if (lastPacketsMissing.length > 2) {
                                Log.w(TAG, "Too many packets missing for timestamp " + highestLastTimestamp + " missing " + lastPacketsMissing.length + ". Discarding.");
                            } else {
                                multipleImageData.put(highestLastTimestamp, lastImageDataHolder);

                                // re-request that data

                                ByteArrayOutputStream bos = new ByteArrayOutputStream(20);
                                DataOutputStream dos = new DataOutputStream(bos);
                                try {
                                    dos.writeBytes(REREQUEST_PACKET_HEADER);
                                    dos.writeInt(highestLastTimestamp);
                                    //int loopIndex = 0;
                                    for (int num : lastPacketsMissing) {
                                        dos.writeShort(num);

                                    /*
                                    loopIndex++;

                                    if (loopIndex < lastPacketsMissing.length) {
                                        dos.writeByte((byte) ',');
                                    }*/
                                    }

                                    returnPacket.setData(bos.toByteArray()); // this crushes the existing data to length
                                    udpSocket.send(returnPacket);

                                    Log.i(TAG, "Rerequesting (at least) " + highestLastTimestamp + " " + lastPacketsMissing[0]);
                                } catch (IOException exc) {
                                    Log.e(TAG, "Problem during sending (rerequest) " + exc.getMessage());
                                }
                            }
                        } else {
                            problemFreeImages++;
                        }
                    } else {
                        Log.w(TAG, "No last image data for "+highestLastTimestamp+" new timestamp "+timestamp);
                    }
                }

                highestLastTimestamp = timestamp;
            }

            if (thisImageDataHolder != null) {
                thisImageDataHolder.add(packetNumber, packetsForThisImage, data, headerLength, data.length - headerLength);

                if (thisImageDataHolder.isDataComplete()) {
                    // TODO avoid sending an old image?

                    byte[] imageData = thisImageDataHolder.getData();

                    Bitmap bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

                    if (bmp == null) {
                        // TODO must be shown more prominently
                        Log.e(TAG, "Found illegal image");

                        int imageSize = imageData.length;
                        if (imageSize >= 5) {
                            Log.e(TAG, "first 5 bytes " + String.format("%x", imageData[0]) + String.format("%x", imageData[1]) + String.format("%x", imageData[2]) + String.format("%x", imageData[3]) + String.format("%x", imageData[4]));
                            Log.e(TAG, "last 5 bytes " + String.format("%x", imageData[imageSize - 5]) + String.format("%x", imageData[imageSize - 4]) + String.format("%x", imageData[imageSize - 3]) + String.format("%x", imageData[imageSize - 2]) + String.format("%x", imageData[imageSize - 1]));
                        }
                    } else {
                        Log.i(TAG, "Found image " + bmp.getWidth());

                        if (imageListener != null) {
                            // TODO kbps
                            imageListener.imagePresent(bmp, timestamp, imageData, 0.0f);
                        }
                    }
                } else {
                    if (packetNumber > lastPacketNumber + 1) {
                        // TODO do something here? something is done when timestamp changes. Maybe wait for a gap (no receving any more packets)?
                    }
                    // else packetNumber < lastPacketNumber is possible for a new image
                }

                if (!isRepairData) {
                    lastPacketNumber = packetNumber;
                }
            } else {
                Log.w(TAG, "No image holder for "+timestamp+" "+packetNumber);
            }


            long now = System.currentTimeMillis();
            if (now - lastStatisticsOutMillis > 1500 && shouldHaveReceivedPackets > 0) {
                double recPerc = (receivedPackets/(double)shouldHaveReceivedPackets) * 100;

                Log.i(TAG, "Received " + receivedPackets + " packets of " + shouldHaveReceivedPackets + " " + (new DecimalFormat("#.##").format(recPerc)) + "% Images no-problem/problem " + problemFreeImages + "/" + problematicImages);

                lastStatisticsOutMillis = now;
            }

            try { Thread.sleep(1); } catch (InterruptedException exc) { }
        }

        Log.w(TAG, "Udp receiver exited");
        udpSocket.close();
    }
}
