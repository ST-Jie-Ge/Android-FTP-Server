#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

/* byte[] paths use real UTF-8, not JNI's modified UTF-8 (supplementary Han/emoji). */
static char *path(JNIEnv *env, jbyteArray bytes) {
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *s = malloc((size_t)n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)s);
    s[n] = 0;
    return s;
}

/**
 * Replacement for renameat2(RENAME_NOREPLACE) on kernels and filesystems that do not
 * implement it — Android's /sdcard has been FUSE since 11 and sdcardfs before that;
 * both reject the flag with EINVAL or ENOSYS far more often than they honour it.
 *
 * Returns 0 on success, otherwise an errno. EXDEV is propagated unchanged so the Java
 * side still recognises a cross-volume move and falls back to copy-then-commit.
 */
static int rename_fallback(const char *source, const char *target) {
    struct stat info;
    if (stat(source, &info) != 0) return errno;
    dev_t device = info.st_dev;
    if (stat(target, &info) == 0 && info.st_dev != device) return EXDEV;

    /* link()+unlink() is the portable atomic no-clobber rename: link() fails with EEXIST
       if the destination appeared, and no window exists in which the target is empty. */
    if (link(source, target) == 0) {
        unlink(source);
        return 0;
    }
    if (errno == EEXIST) return EEXIST;

    /* Directories, and filesystems without hard links (FAT, most FUSE daemons).
       rename(2) itself is atomic and never leaves a hole; only the no-clobber check
       below is a pre-check, so a concurrent create can still win the race. That is a
       far smaller exposure than refusing to rename at all. */
    if (faccessat(AT_FDCWD, target, F_OK, 0) == 0) return EEXIST;
    if (rename(source, target) == 0) return 0;
    return errno;
}

static int rename_no_replace(const char *source, const char *target) {
#ifdef __NR_renameat2
    if (syscall(__NR_renameat2, AT_FDCWD, source, AT_FDCWD, target, RENAME_NOREPLACE) == 0) {
        return 0;
    }
    int error = errno;
    /* ENOSYS/EINVAL/EOPNOTSUPP mean "this kernel or filesystem has no such flag", not
       "this rename is impossible". Falling back keeps renaming usable on FUSE/sdcardfs;
       anything else (EEXIST, EACCES, EXDEV, ...) keeps its original meaning. */
    if (error == ENOSYS || error == EINVAL || error == EOPNOTSUPP) {
        return rename_fallback(source, target);
    }
    return error;
#else
    return rename_fallback(source, target);
#endif
}

JNIEXPORT jint JNICALL
Java_com_github_stjiege_ftpshare_filesystem_NativeMove_renameNoReplace(
        JNIEnv *env, jclass type, jbyteArray from, jbyteArray to) {
    (void)type;
    char *source = path(env, from);
    char *target = path(env, to);
    int result = ENOMEM;
    if (source && target) result = rename_no_replace(source, target);
    free(source);
    free(target);
    return result;
}
