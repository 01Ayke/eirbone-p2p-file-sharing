package org.eirb.eirbone.utils;

public class FileInfos {
  private String filename;
  private long length;
  private int pieceSize;
  private String key;

  public FileInfos(String filename, long length, int pieceSize, String key) {
    this.filename = filename;
    this.length = length;
    this.pieceSize = pieceSize;
    this.key = key;
  }

  //
  public String getFilename() {
    return filename;
  }
  public long getLength() {
    return length;
  }
  public int getPieceSize() {
    return pieceSize;
  }
  public String getKey() {
    return key;
  }

  @Override
  public String toString() {
    return filename + " " + length + " " + pieceSize + " " + key;
  }
}
