#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jlong JNICALL
Java_com_github_stjiege_ftpshare_filesystem_DirectoryNames_00024Native_openDirectory(
        JNIEnv *env, jclass type, jbyteArray bytes) {
    (void)type;
    jsize size = (*env)->GetArrayLength(env, bytes);
    char *path = malloc((size_t)size + 1);
    if (!path) { fail(env, "Cannot allocate directory path"); return 0; }
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) { free(path); return 0; }
    path[size] = 0;
    DIR *directory = opendir(path);
    int error = errno;
    free(path);
    if (!directory) fail(env, strerror(error));
    return (jlong)(intptr_t)directory;
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_stjiege_ftpshare_filesystem_DirectoryNames_00024Native_readDirectory(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    DIR *directory = (DIR *)(intptr_t)handle;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (!entry) { if (errno) fail(env, strerror(errno)); return NULL; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        jsize size = (jsize)strlen(entry->d_name);
        jbyteArray result = (*env)->NewByteArray(env, size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte *)entry->d_name);
        return result;
    }
}

JNIEXPORT void JNICALL
Java_com_github_stjiege_ftpshare_filesystem_DirectoryNames_00024Native_closeDirectory(
        JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (closedir((DIR *)(intptr_t)handle)) fail(env, strerror(errno));
}
