package com.mycompany.controlflujo;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class Id3 {
    public String titulo = "", artista = "", album = "", anio = "";
    public String version = "sin etiquetas";

    public static Id3 leer(File f) throws IOException {
        Id3 tag = new Id3();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            tag.leerV1(raf);
            tag.leerV2(raf);
        }
        return tag;
    }

    @Override
    public String toString() {
        return "Etiquetas (" + version + ")\n"
                + "  Titulo : " + vacio(titulo) + "\n"
                + "  Artista: " + vacio(artista) + "\n"
                + "  Album  : " + vacio(album) + "\n"
                + "  Anio   : " + vacio(anio);
    }

    private static String vacio(String s) {
        return s.isEmpty() ? "(desconocido)" : s;
    }

    
    private void leerV1(RandomAccessFile raf) throws IOException {
        if (raf.length() < 128) return;
        byte[] b = new byte[128];
        raf.seek(raf.length() - 128);
        raf.readFully(b);
        if (b[0] != 'T' || b[1] != 'A' || b[2] != 'G') return;
        titulo = textoV1(b, 3, 30);
        artista = textoV1(b, 33, 30);
        album = textoV1(b, 63, 30);
        anio = textoV1(b, 93, 4);
        version = "ID3v1";
    }

    private static String textoV1(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1).replace("\u0000", "").trim();
    }

    
    private void leerV2(RandomAccessFile raf) throws IOException {
        byte[] h = new byte[10];
        raf.seek(0);
        if (raf.read(h) < 10) return;
        if (h[0] != 'I' || h[1] != 'D' || h[2] != '3') return;

        int ver = h[3] & 0xFF;            
        if (ver < 2 || ver > 4) return;
        int flags = h[5] & 0xFF;
        int size = sincroseguro(h, 6);
        if (size <= 0 || size > raf.length()) return;

        byte[] body = new byte[size];
        raf.readFully(body);
        version = "ID3v2." + ver + (version.equals("ID3v1") ? " + ID3v1" : "");

        int pos = 0;
        if ((flags & 0x40) != 0 && ver >= 3 && body.length >= 4) { 
            pos = (ver == 3) ? 4 + Protocolo.leerInt(body, 0) : sincroseguro(body, 0);
        }

        int idLen = (ver == 2) ? 3 : 4;
        int hdr = (ver == 2) ? 6 : 10;
        while (pos + hdr <= body.length && body[pos] != 0) {
            String id = new String(body, pos, idLen, StandardCharsets.ISO_8859_1);
            int fsize;
            if (ver == 2) {
                fsize = ((body[pos + 3] & 0xFF) << 16) | ((body[pos + 4] & 0xFF) << 8) | (body[pos + 5] & 0xFF);
            } else if (ver == 3) {
                fsize = Protocolo.leerInt(body, pos + 4);
            } else {
                fsize = sincroseguro(body, pos + 4);
            }
            int ini = pos + hdr;
            if (fsize <= 0 || ini + fsize > body.length) break;

            switch (id) {
                case "TIT2": case "TT2": poner(0, body, ini, fsize); break;
                case "TPE1": case "TP1": poner(1, body, ini, fsize); break;
                case "TALB": case "TAL": poner(2, body, ini, fsize); break;
                case "TYER": case "TYE": case "TDRC": poner(3, body, ini, fsize); break;
                default: break;
            }
            pos = ini + fsize;
        }
    }

    private void poner(int campo, byte[] b, int ini, int len) {
        String t = decodificar(b, ini, len);
        if (t.isEmpty()) return;
        if (campo == 0) titulo = t;
        else if (campo == 1) artista = t;
        else if (campo == 2) album = t;
        else anio = t.length() > 4 ? t.substring(0, 4) : t; 
    }

    /** El primer byte del frame de texto indica la codificacion. */
    private static String decodificar(byte[] b, int ini, int len) {
        int enc = b[ini] & 0xFF;
        Charset cs;
        switch (enc) {
            case 1: cs = StandardCharsets.UTF_16; break;    
            case 2: cs = StandardCharsets.UTF_16BE; break;
            case 3: cs = StandardCharsets.UTF_8; break;
            default: cs = StandardCharsets.ISO_8859_1; break;
        }
        return new String(b, ini + 1, len - 1, cs).replace("\u0000", "").trim();
    }

    
    private static int sincroseguro(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
                | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }
}