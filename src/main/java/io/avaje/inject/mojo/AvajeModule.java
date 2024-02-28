package io.avaje.inject.mojo;

import java.util.List;

record AvajeModule(String name, List<String> provides, List<String> requires) {}
