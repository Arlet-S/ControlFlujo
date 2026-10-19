package com.mycompany.controlflujo;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;

public class Servidor {
    static final String DIR_CANCIONES = "canciones";
    static final boolean VERBOSO = false; 

    static int ventana = 5;
    static double probPerdida = 0.0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) ventana = Integer.parseInt(args[0]);
        if (args.length > 1) probPerdida = Double.parseDouble(args[1]);

        new File(DIR_CANCIONES).mkdirs();
        DatagramSocket socket = new DatagramSocket(Protocolo.PUERTO);
        socket.setReuseAddress(true);
        System.out.println("Servidor en el puerto " + Protocolo.PUERTO + " | ventana=" + ventana
                + " paquetes de " + Protocolo.TAM_PAQUETE + " bytes | perdida simulada=" + probPerdida);
        System.out.println("Carpeta de canciones: " + new File(DIR_CANCIONES).getAbsolutePath());

        for (;;) {
            DatagramPacket p = Protocolo.esperar(socket, null, 0, Integer.MAX_VALUE, Protocolo.LISTA, Protocolo.PEDIR);
            if (p == null) continue;
            InetAddress cl = p.getAddress();
            int pto = p.getPort();
            try {
                if (p.getData()[0] == Protocolo.LISTA) {
                    enviarLista(socket, cl, pto);
                } else {
                    DataInputStream in = new DataInputStream(
                            new ByteArrayInputStream(p.getData(), 1, p.getLength() - 1));
                    atender(socket, cl, pto, in.readUTF());
                }
            } catch (IOException e) {
                System.out.println("Peticion invalida de " + cl + ":" + pto + " -> " + e.getMessage());
            }
        }
    }

    static void enviarLista(DatagramSocket s, InetAddress cl, int pto) throws IOException {
        File[] mp3 = new File(DIR_CANCIONES).listFiles((d, n) -> n.toLowerCase().endsWith(".mp3"));
        if (mp3 == null) mp3 = new File[0];
        Arrays.sort(mp3);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeByte(Protocolo.LISTA);
        d.writeInt(mp3.length);
        for (File f : mp3) d.writeUTF(f.getName());
        byte[] b = bo.toByteArray();
        s.send(new DatagramPacket(b, b.length, cl, pto));
        System.out.println("Lista enviada a " + cl + ":" + pto + " (" + mp3.length + " canciones)");
    }

    static void atender(DatagramSocket s, InetAddress cl, int pto, String nombre) throws IOException {
        File f = new File(DIR_CANCIONES, nombre);
        if (!nombre.equals(new File(nombre).getName()) || !f.isFile()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bo);
            d.writeByte(Protocolo.ERROR);
            d.writeUTF("No existe la cancion: " + nombre);
            byte[] b = bo.toByteArray();
            s.send(new DatagramPacket(b, b.length, cl, pto));
            System.out.println("Pidio una cancion inexistente: " + nombre);
            return;
        }

        byte[] datos = Files.readAllBytes(f.toPath());
        int total = (datos.length + Protocolo.TAM_PAQUETE - 1) / Protocolo.TAM_PAQUETE;
        CRC32 crcArchivo = new CRC32();
        crcArchivo.update(datos);
        System.out.println("\nEnviando " + nombre + " a " + cl + ":" + pto + " | " + datos.length
                + " bytes = " + total + " paquetes");

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeByte(Protocolo.META);
        d.writeLong(datos.length);
        d.writeInt(total);
        d.writeInt((int) crcArchivo.getValue());
        byte[] meta = bo.toByteArray();

        boolean listo = false;
        for (int i = 0; i < Protocolo.MAX_REINTENTOS && !listo; i++) {
            s.send(new DatagramPacket(meta, meta.length, cl, pto));
            listo = Protocolo.esperar(s, cl, pto, Protocolo.TIMEOUT_MS, Protocolo.LISTO) != null;
        }
        if (!listo) {
            System.out.println("El cliente no confirmo la metainformacion. Se cancela.");
            return;
        }

        long t0 = System.currentTimeMillis();
        int base = 0;        
        int siguiente = 0;   
        int timeouts = 0, retransmitidos = 0, enviados = 0, ultimoPorc = -1;
        long vence = System.currentTimeMillis() + Protocolo.TIMEOUT_MS;

        while (base < total) {
            while (siguiente < base + ventana && siguiente < total) {
                enviarDato(s, cl, pto, datos, siguiente);
                enviados++;
                siguiente++;
            }
            if (VERBOSO) System.out.println("base=" + base + " siguiente=" + siguiente);
            long resta = vence - System.currentTimeMillis();
            DatagramPacket a = resta > 0
                    ? Protocolo.esperar(s, cl, pto, (int) resta, Protocolo.ACK) : null;

            if (a == null) {
                if (++timeouts > 20) {
                    System.out.println("Demasiados timeouts seguidos. Se cancela la transferencia.");
                    return;
                }
                retransmitidos += siguiente - base;
                System.out.println("  Timeout -> retrocediendo a paquete " + base
                        + " (se reenviaran " + (siguiente - base) + ")");
                siguiente = base;
                vence = System.currentTimeMillis() + Protocolo.TIMEOUT_MS;
            } else {
                int ack = Protocolo.leerInt(a.getData(), 1); 
                if (ack > base && ack <= total) {
                    base = ack;
                    timeouts = 0;
                    vence = System.currentTimeMillis() + Protocolo.TIMEOUT_MS;                 
                }
            }
        }
        System.out.println("Transferencia completa en " + (System.currentTimeMillis() - t0) + " ms | enviados="
                + enviados + " retransmitidos~" + retransmitidos);
    }

    static void enviarDato(DatagramSocket s, InetAddress cl, int pto, byte[] datos, int seq) throws IOException {
        int ini = seq * Protocolo.TAM_PAQUETE;
        int len = Math.min(Protocolo.TAM_PAQUETE, datos.length - ini);
        CRC32 c = new CRC32();
        c.update(datos, ini, len);

        ByteArrayOutputStream bo = new ByteArrayOutputStream(Protocolo.ENCABEZADO + len);
        DataOutputStream d = new DataOutputStream(bo);
        d.writeByte(Protocolo.DATOS);
        d.writeInt(seq);
        d.writeShort(len);
        d.writeInt((int) c.getValue());
        d.write(datos, ini, len);
        byte[] b = bo.toByteArray();

        if (Math.random() < probPerdida) {
            System.out.println("  (simulado) se pierde el paquete " + seq);
            return;
        }
        s.send(new DatagramPacket(b, b.length, cl, pto));
    }
}
