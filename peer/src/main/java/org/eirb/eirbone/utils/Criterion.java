package org.eirb.eirbone.utils;

public class Criterion {

  private String value;
  private CriterionType type;

  public Criterion(String value, CriterionType type) {
    this.value = value;
    this.type = type;
  }

  // TODO: impletement for different CriterionType
  @Override
  public String toString() {
    switch (type) {
      case FILENAME_EQUAL:
        return "filename=" + value;
      case FILE_LENGTH_GT:
        return "filelength>" + value;
      default:
        // TODO: throw error
        return "";
    }
  }
}
