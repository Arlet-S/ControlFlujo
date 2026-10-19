package com.mycompany.controlflujo;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public final class Protocolo {
    public static final int PUERTO = 9999;

    public static final int TAM_PAQUETE = 1024;

    public static final int TIMEOUT_MS = 500;

    public static final int MAX_REINTENTOS = 10;

    public static final byte LISTA = 'L', PEDIR = 'P', META = 'M', LISTO = 'R',
            DATOS = 'D', ACK = 'A', ERROR = 'E';

    
    public static final int ENCABEZADO = 11;

    private Protocolo() {}

    
    public static DatagramPacket esperar(DatagramSocket s, InetAddress addr, int pto,
                                         int ms, byte... tipos) throws IOException {
        long fin = System.currentTimeMillis() + ms;
        for (;;) {
            long resta = fin - System.currentTimeMillis();
            if (resta <= 0) return null;
            s.setSoTimeout((int) resta);
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (p.getLength() == 0) continue;
            if (addr != null && (!p.getAddress().equals(addr) || p.getPort() != pto)) continue;
            byte t = p.getData()[0];
            for (byte esperado : tipos) {
                if (t == esperado) return p;
            }
        }
    }

    public static int leerInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}