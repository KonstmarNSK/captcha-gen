package com.kostya.captchagencore.impl;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class CaptchaGenerator {
    private static final char[] AVAILABLE_SYMBOLS =
            new char[]{
                    'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k',
                    'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u',
                    'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E',
                    'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
                    'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y',
                    'Z', '0', '1', '2', '3', '4', '5', '6', '7', '8',
                    '9'
            };

    private static final Font[][] FONTS = new Font[][]{
            new Font[]{
                    new Font("Comic sans", Font.ITALIC, 40),
                    new Font("Comic sans", Font.BOLD | Font.ITALIC, 50),
                    new Font("Comic sans", Font.ITALIC, 60)
            },

            new Font[]{
                    new Font("Times new roman", Font.ITALIC, 40),
                    new Font("Times new roman", Font.BOLD | Font.ITALIC, 50),
                    new Font("Times new roman", Font.ITALIC, 60)
            },

            new Font[]{
                    new Font("Verdana", Font.ITALIC, 40),
                    new Font("Verdana", Font.BOLD | Font.ITALIC, 50),
                    new Font("Verdana", Font.ITALIC, 60)
            }
    };

    private static final Paint[] PAINTS = new Paint[]{
            new GradientPaint(0, 0, Color.WHITE, 200, 200, Color.CYAN, true),
            new GradientPaint(0, 200, Color.CYAN, 200, 0, Color.WHITE, false),
            new RadialGradientPaint(300, 0, 600, new float[]{0.2f, 0.3f}, new Color[]{Color.CYAN, Color.LIGHT_GRAY}),
            new RadialGradientPaint(0, 100, 500, new float[]{0.1f, 0.6f}, new Color[]{Color.LIGHT_GRAY, Color.CYAN}),
    };


    // TODO: 5/2/19 кэши конфигурировать через конфигурационные файлики!
    // kaptcha   kaptchas in packet   packets in 1 queue   queues count    total         1 response
    // 60 kb        * 10                 *  100                * 10        = 600mb          300 kb  +

    // 60 kb        * 20                 *  100                * 10        = 1.2gb          600 kb

    // 60 kb        * 50                 *  100                * 10        = 3gb            1.5 mb

    // 60 kb        * 100                *  100                * 10        = 6gb            3 mb


    // размер очередей, в которых лежат пакеты с капчами
    private static final int QUEUE_SIZE = 100;
    // кол-во очередей, в которых лежат пакеты с капчами
    private static final int QUEUES_COUNT = 10;
    // кол-во капчей в пакете
    private static final int CAPTCHAS_IN_PACKET = 10;

    // массив, в котором лежат ссылки на очереди, в которых лежат пакеты с капчами
    private static final ArrayBlockingQueue<byte[]>[] CAPTCHA_QUEUES = new ArrayBlockingQueue[QUEUES_COUNT];
    // массив, в котором лежат ссылки на освободившиеся массивы из-под капчи
    private static final ArrayBlockingQueue<byte[]>[] FREE_PACKETS = new ArrayBlockingQueue[QUEUES_COUNT];

    private static final int THREAD_COUNT = 20;
    private static final ExecutorService CAPTCHA_GENERATING_THREAD_POOL = Executors.newFixedThreadPool(THREAD_COUNT);

    private static final Random RND = new Random();

    static {
        // создаем очереди для хранения пакетов с капчами
        for (int i = 0; i < QUEUES_COUNT; i++) {
            CAPTCHA_QUEUES[i] = new ArrayBlockingQueue<>(QUEUE_SIZE, true);
        }

        // генерим свободные массивы под пакеты с капчами (1 массив - 1 пакет, в пакете лежит несколько капч + доп. инфа (все в виде байт))
        for(int j = 0; j< QUEUES_COUNT; j++){
            ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE, true);

            for(int k=0; k<QUEUE_SIZE; k++) {
                queue.add(new byte[60 * 1024 * CAPTCHAS_IN_PACKET]);
            }

            FREE_PACKETS[j] = queue;
        }
    }

    // возвращает byte[], содержащий капчу + доп. инфу (несколько капч!)
    /*
        ФОРМАТ (в порядке следования массива, от начала в конец):

        1 байт - размер 1 слова
        1 байт - размер 2 слова
        1 байт - размер 3 слова
        4 байта - размер капчи (все размеры - в байтах)

        далее - 3 слова друг за другом (char[] -> byte[] каждое) и капча (~ 50 kb)

        дальше все повторяется (т.к. в одном массиве содержится несколько капч)
    */
    public static byte[] getCaptcha() {
        byte[] result = null;

        while (result == null) {
            result = getPacket(CAPTCHA_QUEUES);
        }

        return result;
    }

    // после использования массива, возвращенного из getCaptcha, его нужно обратно вернуть. Обязательно.
    public static void returnArray(byte[] arr) throws InterruptedException{
        putPacket(FREE_PACKETS, arr);
    }

    // рисует слово на картинке в заданной позиции
    private static void drawWord(int startX, int startY, Graphics2D g, char[] word) {
        // искажаем
        AffineTransform transform = g.getTransform();
        transform.shear(RND.nextFloat(), RND.nextFloat());
        g.setTransform(transform);

        // FIXME: 5/3/19  тормозит
        // рисуем слово на картинке
        GlyphVector glyphVector = FONTS[RND.nextInt(3)][RND.nextInt(3)]
                .createGlyphVector(g.getFontRenderContext(), word);
        g.drawGlyphVector(glyphVector, startX, startY);
    }

    static {
        for(int i=0; i< THREAD_COUNT; i++){
            CAPTCHA_GENERATING_THREAD_POOL.execute(new GenerateJob());
        }
    }

    /*public static void main(String[] args) throws InterruptedException, IOException {
        final byte[] nextLine = "\n".getBytes(Charset.forName("utf-8"));

        for(int i=0; i< THREAD_COUNT; i++){
            CAPTCHA_GENERATING_THREAD_POOL.execute(new GenerateJob());
        }

        byte[] packet = getCaptcha();

        try {
            int nextCaptchaShift = 0;
            File rootFolder = new File("./captchas");
            rootFolder.mkdir();

            DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(packet));
            byte[] strBuff = new byte[64];
            byte[] captchaBuff = new byte[60 * 1024];

            for (int currCaptchaIdx = 0; currCaptchaIdx < 5; currCaptchaIdx++) {
                int firstWordLenght = dataInputStream.readByte();
                int secondWordLenght = dataInputStream.readByte();
                int thirdWordLenght = dataInputStream.readByte();

                int captchaSize = dataInputStream.readInt();

                File thisCaptchaFolder = new File(rootFolder, "c-" + currCaptchaIdx);
                thisCaptchaFolder.mkdir();

                //пишем слова
                {
                    File thisCaptchaWordsFile = new File(thisCaptchaFolder, "words.txt");
                    thisCaptchaWordsFile.createNewFile();

                    OutputStream wout = new FileOutputStream(thisCaptchaWordsFile);

                    dataInputStream.read(strBuff, 0, firstWordLenght);
                    wout.write(strBuff, 0, firstWordLenght);
                    wout.write(nextLine);

                    dataInputStream.read(strBuff, 0, secondWordLenght);
                    wout.write(strBuff, 0, secondWordLenght);
                    wout.write(nextLine);

                    dataInputStream.read(strBuff, 0, thirdWordLenght);
                    wout.write(strBuff, 0, thirdWordLenght);
                    wout.write(nextLine);

                    wout.close();
                }

                //пишем капчу
                {
                    File thisCaptchaImg = new File(thisCaptchaFolder, "img.png");
                    thisCaptchaImg.createNewFile();

                    OutputStream out = new FileOutputStream(thisCaptchaImg);
                    dataInputStream.read(captchaBuff, 0, captchaSize);
                    out.write(captchaBuff, 0, captchaSize);
                    out.close();
                }
            }

        }finally {
            returnArray(packet);
        }

    }*/

    private static byte[] getPacket(ArrayBlockingQueue<byte[]>[] queues){
        int randomIndex = RND.nextInt(queues.length);
        byte[] arr = null;
        int i = randomIndex;

        while (null == arr && i < randomIndex + queues.length){
            try {
                arr = queues[i % queues.length].poll(0, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            i++;
        }

        return arr;
    }

    private static boolean putPacket(ArrayBlockingQueue<byte[]>[] queues, byte[] p){
        int randomIndex = RND.nextInt(queues.length);

        for(int i = randomIndex; i < randomIndex + queues.length; i++){
            if(queues[i % queues.length].offer(p)){
                return true;
            }
        }

        return false;
    }

    private static class GenerateJob implements Runnable {
        private static final Logger LOGGER = Logger.getLogger(GenerateJob.class.getSimpleName());
        private int lastQueueIndex = 0;
        private char[][] words = new char[][]{
            new char[RND.nextInt(6) + 1],
            new char[RND.nextInt(6) + 1],
            new char[RND.nextInt(6) + 1],
            new char[RND.nextInt(6) + 1],
            new char[RND.nextInt(6) + 1],
            new char[RND.nextInt(6) + 1],
        };

        @Override
        public void run() {
            // сюда пишем капчу
            byte[] imageBuffer = new byte[60 * 1024];

            ByteArrayOutputStreamCustomImpl baos = new ByteArrayOutputStreamCustomImpl();
            DataOutputStream dataOutputStream = new DataOutputStream(baos);

            while (true) {
                char[] s1 = words[RND.nextInt(words.length)];
                char[] s2 = words[RND.nextInt(words.length)];
                char[] s3 = words[RND.nextInt(words.length)];

                // берем пустой пакет
                byte[] packet = getPacket(FREE_PACKETS);
                int shift = 0;


                for(int i=0; i< CAPTCHAS_IN_PACKET; i++) {

                    //генерим слова для капчи
                    {
                        for (int j = 0; j < s1.length; j++) {
                            s1[j] = AVAILABLE_SYMBOLS[RND.nextInt(AVAILABLE_SYMBOLS.length)];
                        }

                        for (int j = 0; j < s2.length; j++) {
                            s2[j] = AVAILABLE_SYMBOLS[RND.nextInt(AVAILABLE_SYMBOLS.length)];
                        }

                        for (int j = 0; j < s3.length; j++) {
                            s3[j] = AVAILABLE_SYMBOLS[RND.nextInt(AVAILABLE_SYMBOLS.length)];
                        }
                    }

                    BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);

                    //generate background
                    {
                        Graphics2D g1 = img.createGraphics();

                        g1.setPaint(PAINTS[RND.nextInt(4)]);
                        g1.fillRect(0, 0, 400, 400);
                    }

                    //generate words
                    {
                        drawWord(
                                0 + RND.nextInt(5) * 6,
                                60 + RND.nextInt(5) * 6,
                                img.createGraphics(),
                                s1
                        );

                        drawWord(
                                20 + RND.nextInt(5) * 6,
                                140 + RND.nextInt(5) * 6,
                                img.createGraphics(),
                                s2
                        );

                        drawWord(
                                60 + RND.nextInt(5) * 6,
                                230 + RND.nextInt(5) * 6,
                                img.createGraphics(),
                                s3
                        );
                    }

                    // сохраняем картинку в baos
                    baos.setArray(imageBuffer);
                    baos.setOffset(0);

                    try {
                        ImageIO.write(img, "png", baos);


                        int captchaSize = baos.getOffset();


                        baos.setArray(packet);
                        baos.setOffset(shift);

                        // пишем размеры слов (каждый символ - 2 байта)
                        dataOutputStream.writeByte(s1.length * 2);
                        dataOutputStream.writeByte(s2.length * 2);
                        dataOutputStream.writeByte(s3.length * 2);

                        // пишем размер капчи
                        dataOutputStream.writeInt(captchaSize);


                        // первое слово
                        for (int s1i = 0; s1i < s1.length; s1i++) {
                            dataOutputStream.writeChar(s1[s1i]);
                        }

                        // второе слово
                        for (int s2i = 0; s2i < s2.length; s2i++) {
                            dataOutputStream.writeChar(s2[s2i]);
                        }

                        // третье слово
                        for (int s3i = 0; s3i < s3.length; s3i++) {
                            dataOutputStream.writeChar(s3[s3i]);
                        }

                        // на случай кэширования DataOutputStream'ом
                        dataOutputStream.flush();

                        // за эту итерацию добавили: 3 байта (размеры 3 слов), 4 байта (размер капчи) + 3 слова
                        shift += 3 + 4 + s1.length * 2 + s2.length * 2 + s3.length * 2;
                        // пишем в пакет капчу из буфера
                        System.arraycopy(imageBuffer, 0, packet, shift, imageBuffer.length);

                        // и капчу добавили
                        shift += captchaSize;

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                // кладем сгенерированный пакет в очередь
                while (!putPacket(CAPTCHA_QUEUES, packet)){
                    LOGGER.warning("Пакет не записали");
                }
            }
        }
    }
}
