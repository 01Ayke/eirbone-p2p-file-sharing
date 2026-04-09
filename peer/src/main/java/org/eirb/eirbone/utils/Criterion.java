package org.eirb.eirbone.utils;

public class Criterion {

  private String parameter;
  private CriterionType type;

  public Criterion(String parameter, CriterionType type) {
    this.parameter = parameter;
    this.type = type;
  }

  // TODO: impletement for different CriterionType
  @Override
  public String toString() {
    switch (type) {
      case FILENAME_EQUAL:
        return "filename=" + parameter;
      default:
        // TODO: throw error
        return "";
    }
  }
}
