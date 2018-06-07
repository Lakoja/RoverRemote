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
import java.net.SocketTimeoutException;
import java.text.DecimalFormat;
import java.util.LinkedList;
import java.util.Queue;
import java.util.StringTokenizer;

public class UdpRoverConnection extends Thread {
    private static final String TAG = UdpRoverConnection.class.getName();
    private static final String IMAGE_PACKET_HEADER = "RI";
    private static final String REREQUEST_PACKET_HEADER = "MN";
    private static final String CONTROL_PACKET_HEADER = "CT";
    private static final int IMAGE_PACKET_DATA_LENGTH = 1200;
    // packet header + packet number (for image) + of total packets (for image) + timestamp
    private static final int IMAGE_HEADER_LENGTH = 2 + 2 + 2 + 4;
    private static final int MIN_IMAGE_PACKET_LEN = IMAGE_HEADER_LENGTH + 1;
    private static final int MAX_IMAGE_PACKET_LEN = IMAGE_HEADER_LENGTH + IMAGE_PACKET_DATA_LENGTH;

    private static final long ENTRY_TOO_OLD = 300;
    private static final long ENTRY_STATUS_TOO_OLD = 800;
    private static final long ENTRY_IMAGE_STATUS_TOO_OLD = 1800;

    private long lastStatisticsOutMillis = 0;
    private int port;
    private InetAddress returnServerAddress;
    private boolean active = true;
    private ImageListener imageListener;
    private StatusListener statusListener;
    private SparseArray<UdpDataHolder> multipleImageData = new SparseArray<>(11);
    private long lastPacketReceiveMillis = 0;
    private long lastReportedTimestamp = 0;
    private Queue<Float> lastTransfersKbps = new LinkedList<>();
    private float lastTransferKbpsMean = 0;
    private Queue<ControlCommand> commandQueue = new LinkedList<>();
    private long stopSentMillis = 0;
    private ControlCommand lastStopCommand = null;

    private DatagramSocket udpSocket = null;
    private int receivedPackets = 0;
    private int problemFreeImages = 0;
    private int problematicImages = 0;
    private int recoveredImages = 0;
    private int shouldHaveReceivedPackets = 0;
    
    private DatagramPacket packet = null;
    private DatagramPacket returnPacket = null;
    
    public UdpRoverConnection(int port, InetAddress returnServerAddress) {
        this.port = port;
        this.returnServerAddress = returnServerAddress;

        packet = new DatagramPacket(new byte[1500], 1500);
        returnPacket = new DatagramPacket(new byte[500], 500, returnServerAddress, port);
    }

    public void setImageListener(ImageListener imageListener) {
        this.imageListener = imageListener;
    }

    public void setStatusListener(StatusListener statusListener) {
        this.statusListener = statusListener;
    }

    public void stopActive() {
        active = false;
    }
    public void sendControl(String controlRequest) {
        // TODO send confirmation to caller?
        commandQueue.add(new ControlCommand(controlRequest));
    }

    @Override
    public synchronized void start() {
        try {
            udpSocket = new DatagramSocket(port);
            udpSocket.setReceiveBufferSize(30000);
            udpSocket.setSoTimeout(15);
        } catch (SocketException exc) {
            Log.e(TAG, "Cannot create UDP socket " + exc.getMessage());
            return;
        }
        
        receivedPackets = 0;
        problemFreeImages = 0;
        problematicImages = 0;
        recoveredImages = 0;
        shouldHaveReceivedPackets = 0;
        
        super.start();
    }

    @Override
    public void run() {
        Log.i(TAG, "Opened UDP receiver with "+returnServerAddress);


        int lastPacketNumber = -1;
        int highestLastTimestamp = -1;

        while (active) {
            ControlCommand command = null;
            if (stopSentMillis > 0 && System.currentTimeMillis() - stopSentMillis > 250) {
                command = lastStopCommand;
                stopSentMillis = 0;
                lastStopCommand = null;
            } else if (!commandQueue.isEmpty()) {
                ControlCommand queuedCommand = commandQueue.remove();
                if (entryAlive(queuedCommand)) {
                    command = queuedCommand;
                }
            }

            if (command != null) {
                sendCommandPacket(command);

                try { Thread.sleep(1); } catch (InterruptedException exc) { }

                continue;
            }

            boolean received = false;
            try {
                received  = receivePacket();
            } catch (IOException exc) {
                Log.e(TAG, "Cannot receive UDP packet " + exc.getMessage());
                break;
            }

            if (!received) {
                continue;
            }

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

            if (packetHeader.equals(CONTROL_PACKET_HEADER)) {
                String payload = new String(data, 2, packet.getLength() - 2);
                handleControlPacket(payload);

                Thread.yield();

                continue;
            }

            if (packet.getLength() < MIN_IMAGE_PACKET_LEN || packet.getLength() > MAX_IMAGE_PACKET_LEN || !packetHeader.equals(IMAGE_PACKET_HEADER)) {
                Log.e(TAG, "Received bogus image packet "+packetHeader+ " with length "+packet.getLength());
                continue;
            }

            int timestamp = readInt(data, 2);

            if (timestamp < 0) {
                Log.e(TAG, "Received bogus packet timestamp "+timestamp);
                continue;
            }

            int packetNumber = readShort(data, 6);

            if (packetNumber < 0) {
                Log.e(TAG, "Received bogus packet number "+packetNumber);
                continue;
            }

            int packetsForThisImage = readShort(data, 8);

            if (packetsForThisImage < 1) {
                Log.e(TAG, "Received bogus packet total "+packetsForThisImage);
                continue;
            }

            /*
            if (receivedPackets % 100 == 0) {
                Log.i(TAG, "Got 100th packet "+timestamp+" "+packetNumber+"/"+packetsForThisImage+ " of "+shouldHaveReceivedPackets);
            }*/

            if (highestLastTimestamp != -1 && timestamp < highestLastTimestamp - 5000) {
                // Consider this a server reset
                highestLastTimestamp = -1;
                lastReportedTimestamp = 0;
                Log.w(TAG, "Detected a server reset. Resetting timestamp.");
            }

            UdpDataHolder thisImageDataHolder = multipleImageData.get(timestamp);
            boolean isRepairData = false;

            if (thisImageDataHolder == null) {
                if (timestamp < highestLastTimestamp) {
                    // TODO consider server reset (starts from low image timestamps
                    Log.w(TAG, "Discarding data for old image " + timestamp + " highest " + highestLastTimestamp);
                } else {
                    thisImageDataHolder = new UdpDataHolder(timestamp, IMAGE_PACKET_DATA_LENGTH);
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

                    // Only keep current and last image and that only if necessary
                    // TODO beautify
                    if (lastImageDataHolder != null && lastImageDataHolder.isRepairUnderway()) {
                        multipleImageData.put(highestLastTimestamp, lastImageDataHolder);
                    }
                    multipleImageData.put(timestamp, thisImageDataHolder);

                    if (lastImageDataHolder != null) {
                        int[] lastPacketsMissing = lastImageDataHolder.currentlyMissingPackets();

                        // TODO never reached (anymore)
                        if (lastPacketsMissing.length > 0) {
                            problematicImages++;
                        }  else {
                            problemFreeImages++;
                        }

                        if (lastPacketsMissing.length > 0 && !lastImageDataHolder.isRepairUnderway()) {
                            lastImageDataHolder.setRepairUnderway(true);

                            if (lastPacketsMissing.length > 3) {
                                Log.w(TAG, "Too many packets missing for timestamp " + highestLastTimestamp + " missing " + lastPacketsMissing.length + "/"+lastImageDataHolder.getMaximumPacketCount()+". Discarding.");
                            } else {
                                sendRerequestPacket(highestLastTimestamp, lastPacketsMissing);
                            }
                        }
                    } else {
                        Log.w(TAG, "No last image data for "+highestLastTimestamp+" new timestamp "+timestamp);
                    }
                }

                highestLastTimestamp = timestamp;
            }

            if (thisImageDataHolder != null) {
                thisImageDataHolder.add(packetNumber, packetsForThisImage, data, IMAGE_HEADER_LENGTH, packet.getLength() - IMAGE_HEADER_LENGTH);

                if (thisImageDataHolder.isDataComplete()) {
                    handleFinishedImage(thisImageDataHolder);
                } else {
                    if (packetNumber > lastPacketNumber + 1) {
                        //Log.w(TAG, "Missing "+(lastPacketNumber + 1));
                        // TODO do something here? something is done when timestamp changes. Maybe wait for a gap (no receving any more packets)?
                    }
                    // else packetNumber < lastPacketNumber is possible for a new image

                    if (packetNumber == packetsForThisImage - 1 && !thisImageDataHolder.isRepairUnderway()) {
                        handleImageNearlyFinished(highestLastTimestamp, thisImageDataHolder);
                    }
                }

                // TODO is this always correct/needed/correctly named? Is wrong above when finding out about repaired images
                if (!isRepairData) {
                    lastPacketNumber = packetNumber;
                }
            } else {
                Log.w(TAG, "No image holder for "+timestamp+" "+packetNumber);
            }

            printStatistics();

            Thread.yield();
        }

        Log.w(TAG, "Udp receiver exited");
        udpSocket.close();
        udpSocket = null;
    }

    private boolean receivePacket() throws IOException {
        try {
            udpSocket.receive(packet);
        } catch (SocketTimeoutException exc) {
            /* Do nothing special yet (only consider last packet for now)
            if (lastPacketNumber != -1 && highestLastTimestamp != -1) {
                UdpDataHolder lastImageDataHolder = multipleImageData.get(highestLastTimestamp);

                if (null != lastImageDataHolder) {
                    if (!lastImageDataHolder.isDataComplete() && !lastImageDataHolder.isRepairUnderway()) {

                    }
                }
            }*/

            return false;
        }

        lastPacketReceiveMillis = System.currentTimeMillis();
        receivedPackets++;

        return true;
    }

    private void handleControlPacket(String payload) {
        // TODO should probably be "STATUS"
        if (payload.startsWith("VOLT ")) {
            if (statusListener != null) {
                StringTokenizer tokenizer = new StringTokenizer(payload, " ");
                if (tokenizer.countTokens() >= 2) {
                    tokenizer.nextToken();
                    String voltageRaw = tokenizer.nextToken();
                    try {
                        float voltage = Float.parseFloat(voltageRaw);
                        RoverStatus status = new RoverStatus(false, false, false, voltage);

                        statusListener.informRoverStatus(status);
                    } catch (NumberFormatException exc) {
                        Log.e(TAG, "False rover status reply; cannot parse voltage: "+voltageRaw);
                    }
                } else {
                    Log.e(TAG, "False rover status reply; too few tokens: "+payload);
                }
            }
        } else if (payload.equals("OKC 0.00,0.00")) {
            stopSentMillis = 0;
            lastStopCommand = null;

            Log.w(TAG, "Stop confirmed");
        } else {
            //Log.e(TAG, "Got control response "+payload);
        }
    }
    private void handleImageNearlyFinished(int highestLastTimestamp, UdpDataHolder dataHolder) {
        int timestamp = dataHolder.getTimestamp();
        // do something if "last" packet received but some are missing
        // TODO also consider last packet missing (check after some time when packet received - see above SocketTimeoutException)

        // TODO double code above

        int[] packetsMissing = dataHolder.currentlyMissingPackets();

        if (packetsMissing.length > 0) {
            dataHolder.setRepairUnderway(true);

            if (packetsMissing.length > 3) {
                Log.w(TAG, "Too many packets missing for timestamp " + highestLastTimestamp + " missing " + packetsMissing.length + "/"+dataHolder.getMaximumPacketCount()+". Discarding.");
            } else {
                sendRerequestPacket(highestLastTimestamp, packetsMissing);
            }
        }
    }

    private void handleFinishedImage(UdpDataHolder dataHolder) {
        int timestamp = dataHolder.getTimestamp();
        int imageSize = dataHolder.getData().length;
        int receiveMillis = dataHolder.getReceiveMillis();

        if (imageSize > 0 && receiveMillis > 0) {
            float kbps = (imageSize / 1024.0f) / (receiveMillis / 1000.0f);

            // TODO this dequeue and enqueue with mean is rather awkward
            if (lastTransfersKbps.size() == 0) {
                lastTransferKbpsMean = kbps;
            } else if (lastTransfersKbps.size() > 2) {
                // dequeue oldest one
                float oldestKbps = lastTransfersKbps.remove();
                lastTransferKbpsMean = ((lastTransferKbpsMean * (lastTransfersKbps.size() + 1)) - oldestKbps) / lastTransfersKbps.size();
            }

            lastTransferKbpsMean = (lastTransferKbpsMean * lastTransfersKbps.size() + kbps) / (lastTransfersKbps.size() + 1);
            lastTransfersKbps.add(kbps);
        } else {
            Log.w(TAG, "Image receive data bogus; no kbps; image size or bytes 0 "+imageSize+","+receiveMillis);
        }

        if (dataHolder.isRepairUnderway()) {
            recoveredImages++;
            Log.i(TAG, "Found repaired image "+timestamp+" kbps "+lastTransferKbpsMean);
        } else {
            Log.i(TAG, "Found image "+timestamp+" kbps "+lastTransferKbpsMean+" from "+imageSize+" in "+receiveMillis);
        }

        if (timestamp < lastReportedTimestamp) {
            Log.w(TAG, "Complete image too old "+timestamp);
        } else {
            byte[] imageData = dataHolder.getData();

            Bitmap bmp = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

            if (bmp == null) {
                // TODO must be shown more prominently
                Log.e(TAG, "Found illegal image");

                if (imageSize >= 5) {
                    Log.e(TAG, "first 5 bytes " + asHex(imageData, 0, 5));
                    Log.e(TAG, "last 5 bytes " + asHex(imageData, imageSize-5, 5));
                }
            } else {
                //Log.i(TAG, "Found image " + bmp.getWidth());

                if (imageListener != null) {
                    imageListener.imagePresent(bmp, timestamp, imageData, lastTransferKbpsMean);
                    lastReportedTimestamp = timestamp;
                }
            }
        }
    }

    private void sendCommandPacket(ControlCommand command) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(50);
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.writeBytes(CONTROL_PACKET_HEADER);
            dos.writeBytes(command.controlRequest);

            sendPacket(bos.toByteArray());

            if (isStopCommand(command) && stopSentMillis == 0) {
                stopSentMillis = System.currentTimeMillis();
                lastStopCommand = command;
            }
        } catch (IOException exc) {
            Log.e(TAG, "Problem during sending (control) " + exc.getMessage());
        }
    }

    // TODO could take data holder as argument??
    private void sendRerequestPacket(int highestLastTimestamp, int[] lastPacketsMissing) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(40);
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.writeBytes(REREQUEST_PACKET_HEADER);
            dos.writeInt(highestLastTimestamp);
            for (int num : lastPacketsMissing) {
                dos.writeShort(num);
            }

            sendPacket(bos.toByteArray());

            shouldHaveReceivedPackets += lastPacketsMissing.length;

            Log.i(TAG, "Rerequesting (2) (at least) " + highestLastTimestamp + " " + lastPacketsMissing[0]);
        } catch (IOException exc) {
            Log.e(TAG, "Problem during sending (rerequest) " + exc.getMessage());
        }
    }

    private void sendPacket(byte[] data) throws IOException {
        returnPacket.setData(data); // this crushes the existing data to length
        udpSocket.send(returnPacket);
    }

    private int readShort(byte[] data, int offset) {
        return (data[offset] << 8) & 0xff00 | data[offset + 1] & 0xff;
    }

    private int readInt(byte[] data, int offset) {
        return (data[offset] << 24) & 0xff000000 | (data[offset + 1] << 16) & 0xff0000 | (data[offset + 2] << 8) & 0xff00 | data[offset + 3] & 0xff;
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private String asHex(byte[] buf, int offset, int length)
    {
        char[] chars = new char[2 * length];
        for (int i = 0; i < length; ++i)
        {
            chars[2 * i] = HEX_CHARS[(buf[offset + i] & 0xF0) >>> 4];
            chars[2 * i + 1] = HEX_CHARS[buf[offset + i] & 0x0F];
        }

        return new String(chars);
    }

    // TODO do not work so explicit
    private boolean entryAlive(ControlCommand entry) {
        if (entry.controlRequest.endsWith(" 0")) {
            // Transmit every stop regardless of age
            return true;
        } else if (entry.controlRequest.startsWith("status") && entry.age() < ENTRY_STATUS_TOO_OLD) {
            return true;
        } else if (entry.controlRequest.startsWith("image_s") && entry.age() < ENTRY_IMAGE_STATUS_TOO_OLD) {
            return true;
        } else if (entry.age() < ENTRY_TOO_OLD) {
            return true;
        }

        return false;
    }

    private boolean isStopCommand(ControlCommand entry)
    {
        return entry.controlRequest.endsWith(" 0");
    }

    private void printStatistics() {
        long now = System.currentTimeMillis();
        if (now - lastStatisticsOutMillis > 2500 && shouldHaveReceivedPackets > 0) {
            double recPerc = (receivedPackets/(double)shouldHaveReceivedPackets) * 100;

            Log.i(TAG, "Received " + receivedPackets + " packets of "
                    + shouldHaveReceivedPackets + " " + (new DecimalFormat("#.##").format(recPerc))
                    + "% Images no-problem/reconstructed/problem "
                    + problemFreeImages + "/" + recoveredImages + "/" + (problematicImages-recoveredImages));

            lastStatisticsOutMillis = now;
        }
    }
}
