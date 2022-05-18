@SuppressWarnings("JavaModuleNaming")
module kotlinx.coroutines.jdk8 {
    requires kotlin.stdlib;
    requires kotlinx.coroutines.core;

    exports kotlinx.coroutines.future;
    exports kotlinx.coroutines.stream;
    exports kotlinx.coroutines.time;
}
