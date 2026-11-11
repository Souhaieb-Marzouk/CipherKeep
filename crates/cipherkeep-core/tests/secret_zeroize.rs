//! Verifies that [`SecretBytes`] is zeroized before its allocation is freed.
//!
//! The test installs a very small tracking allocator. It records the exact
//! allocation backing a secret, inspects that allocation in `dealloc` (before
//! the system allocator frees it), and fails unless every byte is zero.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Mutex;

use cipherkeep_core::SecretBytes;

static TRACKED_POINTER: AtomicUsize = AtomicUsize::new(0);
static TRACKED_LENGTH: AtomicUsize = AtomicUsize::new(0);
static SAW_ZEROED_ALLOCATION: AtomicBool = AtomicBool::new(false);
static TEST_LOCK: Mutex<()> = Mutex::new(());

struct TrackingAllocator;

// SAFETY: every method delegates to `System`, which is a valid global
// allocator. The inspection in `dealloc` reads a live allocation whose
// pointer and length were recorded by the test, before delegating the free.
unsafe impl GlobalAlloc for TrackingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        // SAFETY: the caller upholds `GlobalAlloc::alloc`'s contract.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        let tracked = TRACKED_POINTER.load(Ordering::SeqCst);
        if tracked != 0 && tracked == pointer as usize {
            let length = TRACKED_LENGTH.load(Ordering::SeqCst).min(layout.size());
            // SAFETY: `pointer` is the live allocation being freed and
            // `length` does not exceed its layout size.
            let bytes = unsafe { std::slice::from_raw_parts(pointer, length) };
            if bytes.iter().all(|byte| *byte == 0) {
                SAW_ZEROED_ALLOCATION.store(true, Ordering::SeqCst);
            }
            TRACKED_POINTER.store(0, Ordering::SeqCst);
        }

        // SAFETY: the pointer and layout come from the caller and have not
        // been freed yet.
        unsafe { System.dealloc(pointer, layout) }
    }
}

#[global_allocator]
static ALLOCATOR: TrackingAllocator = TrackingAllocator;

#[test]
fn secret_bytes_are_zeroed_before_deallocation() {
    let _guard = TEST_LOCK.lock().expect("test lock is not poisoned");
    let secret = SecretBytes::from_slice(b"correct horse battery staple");
    let length = secret.len();
    let pointer = secret.expose_secret().as_ptr() as usize;

    TRACKED_LENGTH.store(length, Ordering::SeqCst);
    TRACKED_POINTER.store(pointer, Ordering::SeqCst);
    SAW_ZEROED_ALLOCATION.store(false, Ordering::SeqCst);

    drop(secret);

    assert!(
        SAW_ZEROED_ALLOCATION.load(Ordering::SeqCst),
        "SecretBytes allocation was freed without all bytes being zero"
    );
}
