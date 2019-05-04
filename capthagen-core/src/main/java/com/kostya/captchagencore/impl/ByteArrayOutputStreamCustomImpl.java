package com.kostya.captchagencore.impl;

import java.io.IOException;
import java.io.OutputStream;

// NOT THREAD-SAFE!!!
class ByteArrayOutputStreamCustomImpl extends OutputStream {
    private byte[] array;
    private int count;


    public ByteArrayOutputStreamCustomImpl() {
    }

    public ByteArrayOutputStreamCustomImpl(byte[] array) {
        this.array = array;
    }

    public byte[] getArray() {
        return array;
    }

    public void setArray(byte[] array) {
        this.array = array;
    }


    public int getOffset() {
        return count;
    }

    public void setOffset(int count) {
        this.count = count;
    }

    @Override
    public void write(int b) throws IOException {
        this.ensureCapacity(1);
        this.array[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b) throws IOException {
        this.ensureCapacity(b.length);
        System.arraycopy(b, 0, this.array, count, b.length);

        count += b.length;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        this.ensureCapacity(len);
        System.arraycopy(b, off, this.array, count, len);

        count += len;
    }

    private void ensureCapacity(int howMuchBytesWannaWrite){
        if(!(this.array.length - 1 > count + howMuchBytesWannaWrite)){
            throw new ArrayIndexOutOfBoundsException("Массив переполнен");
        }
    }
}
