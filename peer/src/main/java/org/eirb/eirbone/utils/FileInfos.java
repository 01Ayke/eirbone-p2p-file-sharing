package org.eirb.eirbone.utils;

public class FileInfos {
  private String filename;
  private int length;
  private int pieceSize;
  private String key;

  public FileInfos(String filename, int length, int pieceSize, String key) {
    this.filename = filename;
    this.length = length;
    this.pieceSize = pieceSize;
    this.key = key;
  }

  @Override
  public String toString() {
    return filename + " " + length + " " + pieceSize + " " + key;
  }
}
