package com.mycompany.controlflujo;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.zip.CRC32;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.Player;


public class Cliente {
    static final String DIR_DESCARGAS = "descargas";

    public static void main(String[] args) throws Exception {
        InetAddress srv = InetAddress.getByName(args.length > 0 ? args[0] : "127.0.0.1");
        DatagramSocket socket = new DatagramSocket();

        List<String> canciones = pedirLista(socket, srv);
        if (canciones == null) {
            System.out.println("El servidor no responde.");
            return;
        }
        if (canciones.isEmpty()) {
            System.out.println("El servidor no tiene canciones.");
            return;
        }

        System.out.println("Canciones disponibles:");
        for (int i = 0; i < canciones.size(); i++) System.out.println("  " + (i + 1) + ") " + canciones.get(i));

        int op;
        if (args.length > 1) {
            op = Integer.parseInt(args[1]);
        } else {
            System.out.print("Elige una: ");
            op = new Scanner(System.in).nextInt();
        }
        if (op < 1 || op > canciones.size()) {
            System.out.println("Opcion invalida.");
            return;
        }

        File archivo = recibir(socket, srv, canciones.get(op - 1));
        if (archivo == null) return;

        // Ya tenemos el archivo completo: etiquetas y reproduccion
        System.out.println("\n" + Id3.leer(archivo));
        reproducir(archivo.getPath());
        socket.close();
    }

    static List<String> pedirLista(DatagramSocket s, InetAddress srv) throws IOException {
        byte[] req = {Protocolo.LISTA};
        for (int i = 0; i < Protocolo.MAX_REINTENTOS; i++) {
            s.send(new DatagramPacket(req, req.length, srv, Protocolo.PUERTO));
            DatagramPacket r = Protocolo.esperar(s, srv, Protocolo.PUERTO, 1000, Protocolo.LISTA);
            if (r != null) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(r.getData(), 1, r.getLength() - 1));
                int n = in.readInt();
                List<String> l = new ArrayList<>();
                for (int k = 0; k < n; k++) l.add(in.readUTF());
                return l;
            }
        }
        return null;
    }

    static File recibir(DatagramSocket s, InetAddress srv, String nombre) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeByte(Protocolo.PEDIR);
        d.writeUTF(nombre);
        byte[] pedir = bo.toByteArray();

        DatagramPacket meta = null;
        for (int i = 0; i < Protocolo.MAX_REINTENTOS && meta == null; i++) {
            s.send(new DatagramPacket(pedir, pedir.length, srv, Protocolo.PUERTO));
            meta = Protocolo.esperar(s, srv, Protocolo.PUERTO, 1000, Protocolo.META, Protocolo.ERROR);
        }
        if (meta == null) {
            System.out.println("El servidor no respondio a la peticion.");
            return null;
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(meta.getData(), 1, meta.getLength() - 1));
        if (meta.getData()[0] == Protocolo.ERROR) {
            System.out.println("Error del servidor: " + in.readUTF());
            return null;
        }
        long tam = in.readLong();
        int total = in.readInt();
        int crcEsperado = in.readInt();
        System.out.println("Recibiendo " + nombre + ": " + tam + " bytes en " + total + " paquetes");
        enviar(s, srv, Protocolo.LISTO, 0, false);

        File destino = new File(DIR_DESCARGAS, nombre);
        destino.getParentFile().mkdirs();
        CRC32 crcTotal = new CRC32();
        int esperado = 0, descartados = 0, corruptos = 0;
        long t0 = System.currentTimeMillis();

        try (FileOutputStream fos = new FileOutputStream(destino)) {
            while (esperado < total) {
                DatagramPacket p = Protocolo.esperar(s, srv, Protocolo.PUERTO, 5000, Protocolo.DATOS, Protocolo.META);
                if (p == null) {
                    System.out.println("Se perdio la comunicacion con el servidor.");
                    return null;
                }
                byte[] b = p.getData();
                if (b[0] == Protocolo.META) {          
                    enviar(s, srv, Protocolo.LISTO, 0, false);
                    continue;
                }

                int seq = Protocolo.leerInt(b, 1);
                int len = ((b[5] & 0xFF) << 8) | (b[6] & 0xFF);
                int crc = Protocolo.leerInt(b, 7);

                boolean valido = len >= 0 && len <= Protocolo.TAM_PAQUETE
                        && Protocolo.ENCABEZADO + len <= p.getLength();
                if (valido) {
                    CRC32 c = new CRC32();
                    c.update(b, Protocolo.ENCABEZADO, len);
                    valido = (int) c.getValue() == crc;
                }
                if (!valido) {
                    corruptos++;                       
                } else if (seq == esperado) {
                    fos.write(b, Protocolo.ENCABEZADO, len);
                    crcTotal.update(b, Protocolo.ENCABEZADO, len);
                    esperado++;
                } else {
                    descartados++;                     
                }
                
                enviar(s, srv, Protocolo.ACK, esperado, true);
            }
        }

        
        while (Protocolo.esperar(s, srv, Protocolo.PUERTO, Protocolo.TIMEOUT_MS * 3, Protocolo.DATOS) != null) {
            enviar(s, srv, Protocolo.ACK, esperado, true);
        }

        System.out.println("Archivo recibido en " + (System.currentTimeMillis() - t0) + " ms | descartados fuera de orden="
                + descartados + " corruptos=" + corruptos);
        if (destino.length() != tam || (int) crcTotal.getValue() != crcEsperado) {
            System.out.println("ATENCION: el archivo no coincide con el original (tamano o CRC). No se reproduce.");
            return null;
        }
        System.out.println("Verificacion CRC32 correcta. Guardado en: " + destino.getPath());
        return destino;
    }

    static void enviar(DatagramSocket s, InetAddress srv, byte tipo, int valor, boolean conInt) throws IOException {
        byte[] b = conInt
                ? new byte[]{tipo, (byte) (valor >>> 24), (byte) (valor >>> 16), (byte) (valor >>> 8), (byte) valor}
                : new byte[]{tipo};
        s.send(new DatagramPacket(b, b.length, srv, Protocolo.PUERTO));
    }

    
    static void reproducir(String ruta) {
        System.out.println("\nReproduciendo: " + ruta);
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(ruta))) {
            new Player(bis).play();
            System.out.println("Reproduccion finalizada.");
        } catch (JavaLayerException | IOException e) {
            System.out.println("No se pudo reproducir: " + e.getMessage());
        }
    }
}
