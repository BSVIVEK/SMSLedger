#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then JAVA_CMD="$JAVA_HOME/bin/java"; else JAVA_CMD=java; fi
if [ ! -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    "$JAVA_CMD" "$PROJECT_DIR/scripts/GradleBootstrap.java" "$PROJECT_DIR"
fi
exec "$JAVA_CMD" -Dorg.gradle.appname=gradlew -classpath "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
