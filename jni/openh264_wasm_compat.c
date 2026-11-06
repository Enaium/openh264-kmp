/*
 * Emscripten compatibility shim.
 *
 * OpenH264's decoder-thread helpers reference `sem_timedwait`, which musl in
 * Emscripten only provides when the module is built with pthreads. The binding
 * never enables decoder worker threads, so the function is unreachable; this
 * definition only satisfies the linker. It is compiled into the WebAssembly
 * module alone.
 */
#if defined(__EMSCRIPTEN__)

#include <errno.h>
#include <semaphore.h>

int sem_timedwait(sem_t *sem, const struct timespec *abs_timeout) {
  (void)sem;
  (void)abs_timeout;
  errno = ETIMEDOUT;
  return -1;
}

#endif /* __EMSCRIPTEN__ */
