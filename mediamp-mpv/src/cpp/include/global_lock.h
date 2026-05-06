//
// Created by StageGuard on 12/29/2024.
//

#ifndef MEDIAMP_GLOBAL_LOCK_H
#define MEDIAMP_GLOBAL_LOCK_H

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>

class CompatibleLock {
public:
    CompatibleLock() { InitializeCriticalSection(&cs_); }
    ~CompatibleLock() { DeleteCriticalSection(&cs_); }
    void lock() { EnterCriticalSection(&cs_); }
    void unlock() { LeaveCriticalSection(&cs_); }
private:
    CRITICAL_SECTION cs_;
};

// RAII
class LockGuard {
public:
    LockGuard(CompatibleLock& lock) : lock_(lock) { lock_.lock(); }
    ~LockGuard() { lock_.unlock();  }
private:
    CompatibleLock& lock_;
};

#define CREATE_LOCK(lock_name) CompatibleLock lock_name
#define LOCK(lock_name) LockGuard guard_##lock_name(lock_name)

#else
#include <pthread.h>

class PthreadRecursiveLock {
public:
    PthreadRecursiveLock() {
        pthread_mutexattr_t attr;
        pthread_mutexattr_init(&attr);
        pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
        pthread_mutex_init(&mutex_, &attr);
        pthread_mutexattr_destroy(&attr);
    }
    ~PthreadRecursiveLock() { pthread_mutex_destroy(&mutex_); }
    void lock() { pthread_mutex_lock(&mutex_); }
    void unlock() { pthread_mutex_unlock(&mutex_); }
private:
    pthread_mutex_t mutex_;
};

class PthreadLockGuard {
public:
    PthreadLockGuard(PthreadRecursiveLock& lock) : lock_(lock) { lock_.lock(); }
    ~PthreadLockGuard() { lock_.unlock(); }
private:
    PthreadRecursiveLock& lock_;
};

#define CREATE_LOCK(lock_name) PthreadRecursiveLock lock_name
#define LOCK(lock_name) PthreadLockGuard guard_##lock_name(lock_name)

#endif

#endif //MEDIAMP_GLOBAL_LOCK_H
