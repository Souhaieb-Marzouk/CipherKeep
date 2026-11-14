let wasm_bindgen = (function(exports) {
    let script_src;
    if (typeof document !== 'undefined' && document.currentScript !== null) {
        script_src = new URL(document.currentScript.src, location.href).toString();
    }

    /**
     * In-memory metadata search index for the extension.
     */
    class WasmSearchIndex {
        __destroy_into_raw() {
            const ptr = this.__wbg_ptr;
            this.__wbg_ptr = 0;
            WasmSearchIndexFinalization.unregister(this);
            return ptr;
        }
        free() {
            const ptr = this.__destroy_into_raw();
            wasm.__wbg_wasmsearchindex_free(ptr, 0);
        }
        /**
         * Clear and zeroize the index.
         */
        clear() {
            wasm.wasmsearchindex_clear(this.__wbg_ptr);
        }
        /**
         * Insert or replace a metadata document.
         *
         * # Errors
         *
         * Returns a JS error object for an invalid id or empty title.
         * @param {Uint8Array | number[]} id
         * @param {string} title
         * @param {string} username
         * @param {string} url
         * @param {string} folder
         * @param {string} notes
         */
        insert(id, title, username, url, folder, notes) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, ptr4, len4, ptr5, len5;
                try {
                    ptr0 = passArray8ToWasm0(id, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passStringToWasm0(title, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len1 = WASM_VECTOR_LEN;
                    ptr2 = passStringToWasm0(username, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len2 = WASM_VECTOR_LEN;
                    ptr3 = passStringToWasm0(url, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len3 = WASM_VECTOR_LEN;
                    ptr4 = passStringToWasm0(folder, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len4 = WASM_VECTOR_LEN;
                    ptr5 = passStringToWasm0(notes, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len5 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr5) { wasm.__wbindgen_export3(ptr5, len5 * 1, 1); }
                    if (ptr4) { wasm.__wbindgen_export3(ptr4, len4 * 1, 1); }
                    if (ptr3) { wasm.__wbindgen_export3(ptr3, len3 * 1, 1); }
                    if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmsearchindex_insert(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, ptr4, len4, ptr5, len5);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                if (r1) {
                    throw takeFromExternrefTable0(r0);
                }
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Number of indexed documents.
         * @returns {number}
         */
        len() {
            const ret = wasm.wasmsearchindex_len(this.__wbg_ptr);
            return ret >>> 0;
        }
        /**
         * Create an empty index.
         */
        constructor() {
            const ret = wasm.wasmsearchindex_new();
            this.__wbg_ptr = ret;
            WasmSearchIndexFinalization.register(this, this.__wbg_ptr, this);
            return this;
        }
        /**
         * Remove a document.
         * @param {Uint8Array | number[]} id
         * @returns {boolean}
         */
        remove(id) {
            const ptr0 = passArray8ToWasm0(id, wasm.__wbindgen_export);
            const len0 = WASM_VECTOR_LEN;
            const ret = wasm.wasmsearchindex_remove(this.__wbg_ptr, ptr0, len0);
            return ret !== 0;
        }
        /**
         * Search metadata and return redacted hits.
         *
         * # Errors
         *
         * Returns a JS error object if serialization fails.
         * @param {string} query
         * @param {number} limit
         * @returns {any}
         */
        search(query, limit) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const val2 = +limit;
                const ptr0 = passStringToWasm0(query, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmsearchindex_search(retptr, this.__wbg_ptr, ptr0, len0, val2);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return takeFromExternrefTable0(r0);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
    }
    if (Symbol.dispose) WasmSearchIndex.prototype[Symbol.dispose] = WasmSearchIndex.prototype.free;
    exports.WasmSearchIndex = WasmSearchIndex;

    class WasmVaultSession {
        static __wrap(ptr) {
            const obj = Object.create(WasmVaultSession.prototype);
            obj.__wbg_ptr = ptr;
            WasmVaultSessionFinalization.register(obj, obj.__wbg_ptr, obj);
            return obj;
        }
        __destroy_into_raw() {
            const ptr = this.__wbg_ptr;
            this.__wbg_ptr = 0;
            WasmVaultSessionFinalization.unregister(this);
            return ptr;
        }
        free() {
            const ptr = this.__destroy_into_raw();
            wasm.__wbg_wasmvaultsession_free(ptr, 0);
        }
        /**
         * Apply a v2 sync record to a legacy JSON payload.
         * @param {Uint8Array | number[]} sync_json
         * @returns {Uint8Array}
         */
        apply_sync_record(sync_json) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(sync_json, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_apply_sync_record(retptr, this.__wbg_ptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v2 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v2;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Persist this vault's header and recovery envelope.
         * @returns {Uint8Array}
         */
        blob() {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                wasm.wasmvaultsession_blob(retptr, this.__wbg_ptr);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var v1 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v1;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Create a vault and return the session with its recovery shares.
         *
         * # Errors
         *
         * Returns a JS error object for a weak password or key-generation failure.
         * @param {Uint8Array | number[]} password
         * @returns {WasmVaultSession}
         */
        static create(password) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(password, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_create(retptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return WasmVaultSession.__wrap(r0);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Join an existing family vault by adopting its vault key.
         * @param {Uint8Array | number[]} password
         * @param {Uint8Array | number[]} vault_id
         * @param {Uint8Array | number[]} vault_key
         * @returns {WasmVaultSession}
         */
        static create_shared(password, vault_id, vault_key) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1, ptr2, len2;
                try {
                    ptr0 = passArray8ToWasm0(password, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(vault_id, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                    ptr2 = passArray8ToWasm0(vault_key, wasm.__wbindgen_export);
                    len2 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_create_shared(retptr, ptr0, len0, ptr1, len1, ptr2, len2);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return WasmVaultSession.__wrap(r0);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * @param {Uint8Array | number[]} item_id
         * @param {Uint8Array | number[]} ciphertext
         * @returns {Uint8Array}
         */
        decrypt_item(item_id, ciphertext) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1;
                try {
                    ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(ciphertext, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_decrypt_item(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v3 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v3;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Delete an item.
         *
         * # Errors
         *
         * Returns a JS error object when the session is locked.
         * @param {Uint8Array | number[]} item_id
         */
        delete_item(item_id) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_delete_item(retptr, this.__wbg_ptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                if (r1) {
                    throw takeFromExternrefTable0(r0);
                }
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Encrypt an item payload for IndexedDB storage without adding it to the
         * in-memory item map.
         *
         * # Errors
         *
         * Returns a JS error object for a malformed id, locked session or crypto
         * failure.
         * @param {Uint8Array | number[]} item_id
         * @param {Uint8Array | number[]} plaintext
         * @returns {Uint8Array}
         */
        encrypt_item(item_id, plaintext) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1;
                try {
                    ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(plaintext, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_encrypt_item(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v3 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v3;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Export a v2 sync record with wrapped item key.
         * @param {Uint8Array | number[]} item_id
         * @param {Uint8Array | number[]} ciphertext
         * @param {Uint8Array | number[]} actor
         * @param {bigint} created_at
         * @param {bigint} counter_start
         * @returns {Uint8Array}
         */
        export_sync_record(item_id, ciphertext, actor, created_at, counter_start) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const val4 = BigInt.asIntN(64, created_at);
                const val5 = BigInt.asIntN(64, counter_start);
                let ptr0, len0, ptr1, len1, ptr2, len2;
                try {
                    ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(ciphertext, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                    ptr2 = passArray8ToWasm0(actor, wasm.__wbindgen_export);
                    len2 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_export_sync_record(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1, ptr2, len2, val4, val5);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v6 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v6;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Consecutive failed unlock attempts.
         * @returns {number}
         */
        failed_attempts() {
            const ret = wasm.wasmvaultsession_failed_attempts(this.__wbg_ptr);
            return ret >>> 0;
        }
        /**
         * Active vault key for owner-initiated wrapping.
         * @returns {Uint8Array}
         */
        family_vault_key() {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                wasm.wasmvaultsession_family_vault_key(retptr, this.__wbg_ptr);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v1 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v1;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Decrypt an item payload.
         *
         * # Errors
         *
         * Returns a JS error object for a missing item, locked session or
         * authentication failure.
         * @param {Uint8Array | number[]} item_id
         * @returns {Uint8Array}
         */
        get_item(item_id) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_get_item(retptr, this.__wbg_ptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v2 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v2;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Whether the session is locked.
         * @returns {boolean}
         */
        is_locked() {
            const ret = wasm.wasmvaultsession_is_locked(this.__wbg_ptr);
            return ret !== 0;
        }
        /**
         * Number of stored items.
         *
         * # Errors
         *
         * Returns a JS error object when the session is locked.
         * @returns {number}
         */
        item_count() {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                wasm.wasmvaultsession_item_count(retptr, this.__wbg_ptr);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return r0 >>> 0;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * List item ids as lowercase hex strings.
         *
         * # Errors
         *
         * Returns a JS error object when the session is locked.
         * @returns {string[]}
         */
        list_item_ids() {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                wasm.wasmvaultsession_list_item_ids(retptr, this.__wbg_ptr);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v1 = getArrayJsValueFromWasm0(r0, r1);
                wasm.__wbindgen_export3(r0, r1 * 4, 4);
                return v1;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Lock and drop the master key/keyring.
         */
        lock() {
            wasm.wasmvaultsession_lock(this.__wbg_ptr);
        }
        /**
         * Migrate an encrypted legacy item into canonical sync JSON.
         * @param {Uint8Array | number[]} item_id
         * @param {Uint8Array | number[]} ciphertext
         * @param {Uint8Array | number[]} actor
         * @param {bigint} created_at
         * @param {bigint} counter_start
         * @returns {Uint8Array}
         */
        migrate_item(item_id, ciphertext, actor, created_at, counter_start) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const val4 = BigInt.asIntN(64, created_at);
                const val5 = BigInt.asIntN(64, counter_start);
                let ptr0, len0, ptr1, len1, ptr2, len2;
                try {
                    ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(ciphertext, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                    ptr2 = passArray8ToWasm0(actor, wasm.__wbindgen_export);
                    len2 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_migrate_item(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1, ptr2, len2, val4, val5);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v6 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v6;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Open a persisted vault in the locked state.
         *
         * Keeps local failed-attempt/backoff state across unlock calls.
         *
         * # Errors
         *
         * Returns a JS error object for a malformed blob.
         * @param {Uint8Array | number[]} blob
         * @returns {WasmVaultSession}
         */
        static open(blob) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(blob, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_open(retptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return WasmVaultSession.__wrap(r0);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Recover with two shares and set a new master password.
         *
         * # Errors
         *
         * Returns a JS error object for bad shares, a weak password or a malformed
         * blob.
         * @param {Uint8Array | number[]} blob
         * @param {string} first
         * @param {string} second
         * @param {Uint8Array | number[]} new_password
         * @returns {WasmVaultSession}
         */
        static recover(blob, first, second, new_password) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3;
                try {
                    ptr0 = passArray8ToWasm0(blob, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passStringToWasm0(first, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len1 = WASM_VECTOR_LEN;
                    ptr2 = passStringToWasm0(second, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                    len2 = WASM_VECTOR_LEN;
                    ptr3 = passArray8ToWasm0(new_password, wasm.__wbindgen_export);
                    len3 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr3) { wasm.__wbindgen_export3(ptr3, len3 * 1, 1); }
                    if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_recover(retptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return WasmVaultSession.__wrap(r0);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * One-time recovery shares; empty after a normal unlock.
         * @returns {string[]}
         */
        recovery_shares() {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                wasm.wasmvaultsession_recovery_shares(retptr, this.__wbg_ptr);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var v1 = getArrayJsValueFromWasm0(r0, r1);
                wasm.__wbindgen_export3(r0, r1 * 4, 4);
                return v1;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Seconds until another unlock is allowed.
         * @returns {number}
         */
        retry_after() {
            const ret = wasm.wasmvaultsession_retry_after(this.__wbg_ptr);
            return ret >>> 0;
        }
        /**
         * Rotate the vault key and return the new blob plus rekeyed item blobs.
         * @param {string} items_json
         * @returns {string}
         */
        rotate_family_key(items_json) {
            let deferred3_0;
            let deferred3_1;
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passStringToWasm0(items_json, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_rotate_family_key(retptr, this.__wbg_ptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                var ptr2 = r0;
                var len2 = r1;
                if (r3) {
                    ptr2 = 0; len2 = 0;
                    throw takeFromExternrefTable0(r2);
                }
                deferred3_0 = ptr2;
                deferred3_1 = len2;
                return getStringFromWasm0(ptr2, len2);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
                wasm.__wbindgen_export3(deferred3_0, deferred3_1, 1);
            }
        }
        /**
         * Encrypt and store an opaque item payload.
         *
         * # Errors
         *
         * Returns a JS error object for a malformed id, locked session or crypto
         * failure.
         * @param {Uint8Array | number[]} item_id
         * @param {Uint8Array | number[]} plaintext
         */
        save_item(item_id, plaintext) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1;
                try {
                    ptr0 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(plaintext, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_save_item(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                if (r1) {
                    throw takeFromExternrefTable0(r0);
                }
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Unlock a persisted vault blob.
         *
         * # Errors
         *
         * Returns a JS error object for a wrong password, corrupt blob or backoff.
         * @param {Uint8Array | number[]} blob
         * @param {Uint8Array | number[]} password
         * @returns {WasmVaultSession}
         */
        static unlock(blob, password) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1;
                try {
                    ptr0 = passArray8ToWasm0(blob, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(password, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_unlock(retptr, ptr0, len0, ptr1, len1);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                if (r2) {
                    throw takeFromExternrefTable0(r1);
                }
                return WasmVaultSession.__wrap(r0);
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Unlock this handle again after `lock`.
         *
         * # Errors
         *
         * As `unlock`.
         * @param {Uint8Array | number[]} password
         */
        unlock_again(password) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(password, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_unlock_again(retptr, this.__wbg_ptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                if (r1) {
                    throw takeFromExternrefTable0(r0);
                }
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Decrypt an item ciphertext returned by `encrypt_item`.
         *
         * # Errors
         *
         * Returns a JS error object for a malformed id, locked session or
         * authentication failure.
         * The vault id used for sync namespaces and AAD.
         * @returns {Uint8Array}
         */
        vault_id() {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                wasm.wasmvaultsession_vault_id(retptr, this.__wbg_ptr);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v1 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v1;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Unlock the keyring using a WebAuthn PRF output.
         * @param {Uint8Array | number[]} sealed
         * @param {Uint8Array | number[]} prf_output
         */
        webauthn_open(sealed, prf_output) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                let ptr0, len0, ptr1, len1;
                try {
                    ptr0 = passArray8ToWasm0(sealed, wasm.__wbindgen_export);
                    len0 = WASM_VECTOR_LEN;
                    ptr1 = passArray8ToWasm0(prf_output, wasm.__wbindgen_export);
                    len1 = WASM_VECTOR_LEN;
                } catch (e) {
                    if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                    if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                    throw e;
                }
                wasm.wasmvaultsession_webauthn_open(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                if (r1) {
                    throw takeFromExternrefTable0(r0);
                }
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
        /**
         * Seal a second keyring copy under a WebAuthn PRF output.
         * @param {Uint8Array | number[]} prf_output
         * @returns {Uint8Array}
         */
        webauthn_seal(prf_output) {
            try {
                const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
                const ptr0 = passArray8ToWasm0(prf_output, wasm.__wbindgen_export);
                const len0 = WASM_VECTOR_LEN;
                wasm.wasmvaultsession_webauthn_seal(retptr, this.__wbg_ptr, ptr0, len0);
                var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
                var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
                var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
                var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
                if (r3) {
                    throw takeFromExternrefTable0(r2);
                }
                var v2 = getArrayU8FromWasm0(r0, r1).slice();
                wasm.__wbindgen_export3(r0, r1 * 1, 1);
                return v2;
            } finally {
                wasm.__wbindgen_add_to_stack_pointer(16);
            }
        }
    }
    if (Symbol.dispose) WasmVaultSession.prototype[Symbol.dispose] = WasmVaultSession.prototype.free;
    exports.WasmVaultSession = WasmVaultSession;

    /**
     * Decrypt a Proton Pass `data.pgp` payload with its export passphrase.
     * @param {Uint8Array | number[]} bytes
     * @param {string} passphrase
     * @returns {Uint8Array}
     */
    function decrypt_proton_pgp(bytes, passphrase) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passArray8ToWasm0(bytes, wasm.__wbindgen_export);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(passphrase, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.decrypt_proton_pgp(retptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeFromExternrefTable0(r2);
            }
            var v3 = getArrayU8FromWasm0(r0, r1).slice();
            wasm.__wbindgen_export3(r0, r1 * 1, 1);
            return v3;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.decrypt_proton_pgp = decrypt_proton_pgp;

    /**
     * Encrypt portable item JSON with a separate export password.
     *
     * # Errors
     *
     * Returns a JS error object for a weak password or crypto failure.
     * @param {Uint8Array | number[]} items_json
     * @param {string} password
     * @returns {Uint8Array}
     */
    function export_portable(items_json, password) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passArray8ToWasm0(items_json, wasm.__wbindgen_export);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(password, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.export_portable(retptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeFromExternrefTable0(r2);
            }
            var v3 = getArrayU8FromWasm0(r0, r1).slice();
            wasm.__wbindgen_export3(r0, r1 * 1, 1);
            return v3;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.export_portable = export_portable;

    /**
     * Generate a family member identity as JSON.
     * @param {string} id
     * @param {string} role
     * @returns {string}
     */
    function family_identity(id, role) {
        let deferred4_0;
        let deferred4_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passStringToWasm0(id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(role, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.family_identity(retptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr3 = r0;
            var len3 = r1;
            if (r3) {
                ptr3 = 0; len3 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred4_0 = ptr3;
            deferred4_1 = len3;
            return getStringFromWasm0(ptr3, len3);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred4_0, deferred4_1, 1);
        }
    }
    exports.family_identity = family_identity;

    /**
     * Unwrap a member vault key as JSON.
     * @param {string} vault_id
     * @param {string} member_id
     * @param {string} ephemeral_public
     * @param {string} nonce
     * @param {string} ciphertext
     * @param {string} role
     * @param {string} secret
     * @returns {string}
     */
    function family_unwrap(vault_id, member_id, ephemeral_public, nonce, ciphertext, role, secret) {
        let deferred9_0;
        let deferred9_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, ptr4, len4, ptr5, len5, ptr6, len6;
            try {
                ptr0 = passStringToWasm0(vault_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(member_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
                ptr2 = passStringToWasm0(ephemeral_public, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len2 = WASM_VECTOR_LEN;
                ptr3 = passStringToWasm0(nonce, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len3 = WASM_VECTOR_LEN;
                ptr4 = passStringToWasm0(ciphertext, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len4 = WASM_VECTOR_LEN;
                ptr5 = passStringToWasm0(role, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len5 = WASM_VECTOR_LEN;
                ptr6 = passStringToWasm0(secret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len6 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr6) { wasm.__wbindgen_export3(ptr6, len6 * 1, 1); }
                if (ptr5) { wasm.__wbindgen_export3(ptr5, len5 * 1, 1); }
                if (ptr4) { wasm.__wbindgen_export3(ptr4, len4 * 1, 1); }
                if (ptr3) { wasm.__wbindgen_export3(ptr3, len3 * 1, 1); }
                if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.family_unwrap(retptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, ptr4, len4, ptr5, len5, ptr6, len6);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr8 = r0;
            var len8 = r1;
            if (r3) {
                ptr8 = 0; len8 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred9_0 = ptr8;
            deferred9_1 = len8;
            return getStringFromWasm0(ptr8, len8);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred9_0, deferred9_1, 1);
        }
    }
    exports.family_unwrap = family_unwrap;

    /**
     * Wrap a vault key to a member as JSON.
     * @param {string} vault_id
     * @param {string} vault_key
     * @param {string} member_id
     * @param {string} member_public
     * @param {string} role
     * @returns {string}
     */
    function family_wrap(vault_id, vault_key, member_id, member_public, role) {
        let deferred7_0;
        let deferred7_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, ptr4, len4;
            try {
                ptr0 = passStringToWasm0(vault_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(vault_key, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
                ptr2 = passStringToWasm0(member_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len2 = WASM_VECTOR_LEN;
                ptr3 = passStringToWasm0(member_public, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len3 = WASM_VECTOR_LEN;
                ptr4 = passStringToWasm0(role, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len4 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr4) { wasm.__wbindgen_export3(ptr4, len4 * 1, 1); }
                if (ptr3) { wasm.__wbindgen_export3(ptr3, len3 * 1, 1); }
                if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.family_wrap(retptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, ptr4, len4);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr6 = r0;
            var len6 = r1;
            if (r3) {
                ptr6 = 0; len6 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred7_0 = ptr6;
            deferred7_1 = len6;
            return getStringFromWasm0(ptr6, len6);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred7_0, deferred7_1, 1);
        }
    }
    exports.family_wrap = family_wrap;

    /**
     * Generate an EFF-style passphrase with the shared core generator.
     *
     * # Errors
     *
     * Returns a JS `{ code, message }` object for an invalid policy or CSPRNG
     * failure.
     * @param {number} words
     * @param {string} separator
     * @returns {any}
     */
    function generate_passphrase(words, separator) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const val2 = +words;
            const ptr0 = passStringToWasm0(separator, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.generate_passphrase(retptr, val2, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeFromExternrefTable0(r1);
            }
            return takeFromExternrefTable0(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.generate_passphrase = generate_passphrase;

    /**
     * Generate a password with the shared core generator.
     *
     * # Errors
     *
     * Returns a JS `{ code, message }` object for an impossible policy or CSPRNG
     * failure.
     * @param {number} length
     * @param {boolean} lowercase
     * @param {boolean} uppercase
     * @param {boolean} digits
     * @param {boolean} symbols
     * @param {boolean} exclude_ambiguous
     * @returns {any}
     */
    function generate_password(length, lowercase, uppercase, digits, symbols, exclude_ambiguous) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            wasm.generate_password(retptr, length, lowercase, uppercase, digits, symbols, exclude_ambiguous);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeFromExternrefTable0(r1);
            }
            return takeFromExternrefTable0(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.generate_password = generate_password;

    /**
     * Local password health report as JSON.
     * @param {string} items_json
     * @param {bigint} now
     * @param {bigint} stale_secs
     * @returns {string}
     */
    function health_report(items_json, now, stale_secs) {
        let deferred5_0;
        let deferred5_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const val2 = BigInt.asIntN(64, now);
            const val3 = BigInt.asIntN(64, stale_secs);
            const ptr0 = passStringToWasm0(items_json, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.health_report(retptr, ptr0, len0, val2, val3);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr4 = r0;
            var len4 = r1;
            if (r3) {
                ptr4 = 0; len4 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred5_0 = ptr4;
            deferred5_1 = len4;
            return getStringFromWasm0(ptr4, len4);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred5_0, deferred5_1, 1);
        }
    }
    exports.health_report = health_report;

    /**
     * Parse a plaintext password-manager export into normalized JSON.
     * @param {Uint8Array | number[]} bytes
     * @param {boolean} merge_duplicates
     * @returns {string}
     */
    function import_plaintext_json(bytes, merge_duplicates) {
        let deferred4_0;
        let deferred4_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const val2 = +merge_duplicates;
            const ptr0 = passArray8ToWasm0(bytes, wasm.__wbindgen_export);
            const len0 = WASM_VECTOR_LEN;
            wasm.import_plaintext_json(retptr, ptr0, len0, val2);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr3 = r0;
            var len3 = r1;
            if (r3) {
                ptr3 = 0; len3 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred4_0 = ptr3;
            deferred4_1 = len3;
            return getStringFromWasm0(ptr3, len3);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred4_0, deferred4_1, 1);
        }
    }
    exports.import_plaintext_json = import_plaintext_json;

    /**
     * Decrypt a portable export and return item JSON.
     *
     * # Errors
     *
     * Returns a JS error object for a wrong password, tamper or malformed header.
     * @param {Uint8Array | number[]} bytes
     * @param {string} password
     * @returns {Uint8Array}
     */
    function import_portable(bytes, password) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passArray8ToWasm0(bytes, wasm.__wbindgen_export);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(password, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.import_portable(retptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeFromExternrefTable0(r2);
            }
            var v3 = getArrayU8FromWasm0(r0, r1).slice();
            wasm.__wbindgen_export3(r0, r1 * 1, 1);
            return v3;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.import_portable = import_portable;

    /**
     * Merge local and remote canonical ItemRecord JSON strings.
     *
     * Returns `{records: [json string], conflicts: [{item, field, kind}]}`.
     * @param {string[]} local
     * @param {string[]} remote
     * @returns {string}
     */
    function merge_sync_records(local, remote) {
        let deferred4_0;
        let deferred4_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passArrayJsValueToWasm0(local, wasm.__wbindgen_export);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passArrayJsValueToWasm0(remote, wasm.__wbindgen_export);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export7(ptr1, len1); wasm.__wbindgen_export3(ptr1, len1 * 4, 4); }
                if (ptr0) { wasm.__wbindgen_export7(ptr0, len0); wasm.__wbindgen_export3(ptr0, len0 * 4, 4); }
                throw e;
            }
            wasm.merge_sync_records(retptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr3 = r0;
            var len3 = r1;
            if (r3) {
                ptr3 = 0; len3 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred4_0 = ptr3;
            deferred4_1 = len3;
            return getStringFromWasm0(ptr3, len3);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred4_0, deferred4_1, 1);
        }
    }
    exports.merge_sync_records = merge_sync_records;

    /**
     * Merge V2 sync records, preserving each item's wrapped key.
     * @param {string[]} local
     * @param {string[]} remote
     * @returns {string}
     */
    function merge_sync_records_v2(local, remote) {
        let deferred4_0;
        let deferred4_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passArrayJsValueToWasm0(local, wasm.__wbindgen_export);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passArrayJsValueToWasm0(remote, wasm.__wbindgen_export);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export7(ptr1, len1); wasm.__wbindgen_export3(ptr1, len1 * 4, 4); }
                if (ptr0) { wasm.__wbindgen_export7(ptr0, len0); wasm.__wbindgen_export3(ptr0, len0 * 4, 4); }
                throw e;
            }
            wasm.merge_sync_records_v2(retptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr3 = r0;
            var len3 = r1;
            if (r3) {
                ptr3 = 0; len3 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred4_0 = ptr3;
            deferred4_1 = len3;
            return getStringFromWasm0(ptr3, len3);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred4_0, deferred4_1, 1);
        }
    }
    exports.merge_sync_records_v2 = merge_sync_records_v2;

    /**
     * Migrate legacy plaintext JSON into canonical encrypted sync JSON.
     * @param {Uint8Array | number[]} json
     * @param {Uint8Array | number[]} item_id
     * @param {Uint8Array | number[]} item_key
     * @param {Uint8Array | number[]} actor
     * @param {bigint} created_at
     * @param {bigint} counter_start
     * @returns {Uint8Array}
     */
    function migrate_legacy_item(json, item_id, item_key, actor, created_at, counter_start) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const val5 = BigInt.asIntN(64, created_at);
            const val6 = BigInt.asIntN(64, counter_start);
            let ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3;
            try {
                ptr0 = passArray8ToWasm0(json, wasm.__wbindgen_export);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passArray8ToWasm0(item_id, wasm.__wbindgen_export);
                len1 = WASM_VECTOR_LEN;
                ptr2 = passArray8ToWasm0(item_key, wasm.__wbindgen_export);
                len2 = WASM_VECTOR_LEN;
                ptr3 = passArray8ToWasm0(actor, wasm.__wbindgen_export);
                len3 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr3) { wasm.__wbindgen_export3(ptr3, len3 * 1, 1); }
                if (ptr2) { wasm.__wbindgen_export3(ptr2, len2 * 1, 1); }
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.migrate_legacy_item(retptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3, val5, val6);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeFromExternrefTable0(r2);
            }
            var v7 = getArrayU8FromWasm0(r0, r1).slice();
            wasm.__wbindgen_export3(r0, r1 * 1, 1);
            return v7;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.migrate_legacy_item = migrate_legacy_item;

    /**
     * Offline phishing/homograph assessment for an autofill origin.
     * @param {string} url
     * @returns {string}
     */
    function phishing_check(url) {
        let deferred3_0;
        let deferred3_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const ptr0 = passStringToWasm0(url, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.phishing_check(retptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr2 = r0;
            var len2 = r1;
            if (r3) {
                ptr2 = 0; len2 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred3_0 = ptr2;
            deferred3_1 = len2;
            return getStringFromWasm0(ptr2, len2);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred3_0, deferred3_1, 1);
        }
    }
    exports.phishing_check = phishing_check;

    /**
     * Resolve one V2 field conflict by choosing left or right.
     * @param {string} local
     * @param {string} remote
     * @param {number} field_id
     * @param {boolean} keep_right
     * @returns {string}
     */
    function resolve_sync_conflict(local, remote, field_id, keep_right) {
        let deferred6_0;
        let deferred6_1;
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const val3 = +field_id;
            const val4 = +keep_right;
            let ptr0, len0, ptr1, len1;
            try {
                ptr0 = passStringToWasm0(local, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len0 = WASM_VECTOR_LEN;
                ptr1 = passStringToWasm0(remote, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                len1 = WASM_VECTOR_LEN;
            } catch (e) {
                if (ptr1) { wasm.__wbindgen_export3(ptr1, len1 * 1, 1); }
                if (ptr0) { wasm.__wbindgen_export3(ptr0, len0 * 1, 1); }
                throw e;
            }
            wasm.resolve_sync_conflict(retptr, ptr0, len0, ptr1, len1, val3, val4);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            var ptr5 = r0;
            var len5 = r1;
            if (r3) {
                ptr5 = 0; len5 = 0;
                throw takeFromExternrefTable0(r2);
            }
            deferred6_0 = ptr5;
            deferred6_1 = len5;
            return getStringFromWasm0(ptr5, len5);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
            wasm.__wbindgen_export3(deferred6_0, deferred6_1, 1);
        }
    }
    exports.resolve_sync_conflict = resolve_sync_conflict;

    /**
     * Generate a TOTP code from an otpauth URI.
     *
     * # Errors
     *
     * Returns a JS `{ code, message }` object for an invalid URI.
     * @param {string} uri
     * @param {number} unix_secs
     * @returns {any}
     */
    function totp_code(uri, unix_secs) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16) >>> 0;
            const val2 = +unix_secs;
            const ptr0 = passStringToWasm0(uri, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.totp_code(retptr, ptr0, len0, val2);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeFromExternrefTable0(r1);
            }
            return takeFromExternrefTable0(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    exports.totp_code = totp_code;
    function __wbg_get_imports() {
        const import0 = {
            __proto__: null,
            __wbg_Error_208446807d0d1e30: function(arg0, arg1) {
                const ret = Error(getStringFromWasm0(arg0, arg1));
                return ret;
            },
            __wbg___wbindgen_is_function_741ff2bb612836f5: function(arg0) {
                const ret = typeof(arg0) === 'function';
                return ret;
            },
            __wbg___wbindgen_is_object_8b968c3ab8e96595: function(arg0) {
                const val = arg0;
                const ret = typeof(val) === 'object' && val !== null;
                return ret;
            },
            __wbg___wbindgen_is_string_fc456fee5d3317de: function(arg0) {
                const ret = typeof(arg0) === 'string';
                return ret;
            },
            __wbg___wbindgen_is_undefined_be5b34249ff1fc15: function(arg0) {
                const ret = arg0 === undefined;
                return ret;
            },
            __wbg___wbindgen_string_get_7ce8eee7fd327545: function(arg0, arg1) {
                const obj = arg1;
                const ret = typeof(obj) === 'string' ? obj : undefined;
                var ptr1 = isLikeNone(ret) ? 0 : passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
                var len1 = WASM_VECTOR_LEN;
                getDataViewMemory0().setInt32((arg0 >>> 0) + 4 * 1, len1, true);
                getDataViewMemory0().setInt32((arg0 >>> 0) + 4 * 0, ptr1, true);
            },
            __wbg___wbindgen_throw_4c88d8bf264fd984: function(arg0, arg1) {
                throw new Error(getStringFromWasm0(arg0, arg1));
            },
            __wbg_call_a410eb4b59ed5965: function() { return handleError(function (arg0, arg1, arg2) {
                const ret = arg0.call(arg1, arg2);
                return ret;
            }, arguments); },
            __wbg_crypto_23f565a907d30078: function(arg0) {
                const ret = arg0.crypto;
                return ret;
            },
            __wbg_getRandomValues_d2908e0b99612b91: function() { return handleError(function (arg0, arg1) {
                arg0.getRandomValues(arg1);
            }, arguments); },
            __wbg_length_186163212ffe79c3: function(arg0) {
                const ret = arg0.length;
                return ret;
            },
            __wbg_msCrypto_d5f06fad6bf1409b: function(arg0) {
                const ret = arg0.msCrypto;
                return ret;
            },
            __wbg_new_37eb45aaf5eeb820: function() {
                const ret = new Array();
                return ret;
            },
            __wbg_new_cd4348290afae586: function() {
                const ret = new Object();
                return ret;
            },
            __wbg_new_with_length_01b143a7e5dd5a3e: function(arg0) {
                const ret = new Uint8Array(arg0 >>> 0);
                return ret;
            },
            __wbg_node_86166ff8ce1f2002: function(arg0) {
                const ret = arg0.node;
                return ret;
            },
            __wbg_now_463ac581d2fc7ea3: function() {
                const ret = Date.now();
                return ret;
            },
            __wbg_process_91f425a63f58e399: function(arg0) {
                const ret = arg0.process;
                return ret;
            },
            __wbg_prototypesetcall_13162965d1fe7aa3: function(arg0, arg1, arg2) {
                Uint8Array.prototype.set.call(getArrayU8FromWasm0(arg0, arg1), arg2);
            },
            __wbg_randomFillSync_34b023059e3363de: function() { return handleError(function (arg0, arg1) {
                arg0.randomFillSync(arg1);
            }, arguments); },
            __wbg_require_9a7d8cc2e1d275c0: function() { return handleError(function () {
                const ret = module.require;
                return ret;
            }, arguments); },
            __wbg_set_d2d33b52458e984a: function(arg0, arg1, arg2) {
                arg0[arg1 >>> 0] = arg2;
            },
            __wbg_set_e4b9a833d288759a: function(arg0, arg1, arg2) {
                arg0[arg1] = arg2;
            },
            __wbg_static_accessor_GLOBAL_THIS_003ee833af9699fc: function() {
                const ret = typeof globalThis === 'undefined' ? undefined : globalThis;
                return isLikeNone(ret) ? 0 : addToExternrefTable0(ret);
            },
            __wbg_static_accessor_GLOBAL_e4293fb22cbe77da: function() {
                const ret = typeof global === 'undefined' ? undefined : global;
                return isLikeNone(ret) ? 0 : addToExternrefTable0(ret);
            },
            __wbg_static_accessor_SELF_53bb20457401453d: function() {
                const ret = typeof self === 'undefined' ? undefined : self;
                return isLikeNone(ret) ? 0 : addToExternrefTable0(ret);
            },
            __wbg_static_accessor_WINDOW_b5e0810bfecb7feb: function() {
                const ret = typeof window === 'undefined' ? undefined : window;
                return isLikeNone(ret) ? 0 : addToExternrefTable0(ret);
            },
            __wbg_subarray_58f388a9247ac0bb: function(arg0, arg1, arg2) {
                const ret = arg0.subarray(arg1 >>> 0, arg2 >>> 0);
                return ret;
            },
            __wbg_versions_6e128a981b0df859: function(arg0) {
                const ret = arg0.versions;
                return ret;
            },
            __wbindgen_generic_0000000000000001: function(arg0) {
                // Cast intrinsic for `F64 -> Externref`.
                const ret = arg0;
                return ret;
            },
            __wbindgen_generic_0000000000000002: function(arg0, arg1) {
                // Cast intrinsic for `Ref(Slice(U8)) -> NamedExternref("Uint8Array")`.
                const ret = getArrayU8FromWasm0(arg0, arg1);
                return ret;
            },
            __wbindgen_generic_0000000000000003: function(arg0, arg1) {
                // Cast intrinsic for `Ref(String) -> Externref`.
                const ret = getStringFromWasm0(arg0, arg1);
                return ret;
            },
            __wbindgen_generic_0000000000000004: function(arg0) {
                // Cast intrinsic for `U64 -> Externref`.
                const ret = BigInt.asUintN(64, arg0);
                return ret;
            },
            __wbindgen_init_externref_table: function() {
                const table = wasm.__wbindgen_externrefs;
                table.set(0, undefined);
                table.set(1024, undefined);
                table.set(1025, null);
                table.set(1026, true);
                table.set(1027, false);
            },
        };
        return {
            __proto__: null,
            "./cipherkeep_wasm_bg.js": import0,
        };
    }

    const WasmSearchIndexFinalization = (typeof FinalizationRegistry === 'undefined')
        ? { register: () => {}, unregister: () => {} }
        : new FinalizationRegistry(ptr => wasm.__wbg_wasmsearchindex_free(ptr, 1));
    const WasmVaultSessionFinalization = (typeof FinalizationRegistry === 'undefined')
        ? { register: () => {}, unregister: () => {} }
        : new FinalizationRegistry(ptr => wasm.__wbg_wasmvaultsession_free(ptr, 1));

    function addToExternrefTable0(obj) {
        const idx = wasm.__wbindgen_export5();
        wasm.__wbindgen_externrefs.set(idx, obj);
        return idx;
    }

    function getArrayJsValueFromWasm0(ptr, len) {
        ptr = ptr >>> 0;
        const mem = getDataViewMemory0();
        const result = [];
        for (let i = ptr; i < ptr + 4 * len; i += 4) {
            result.push(wasm.__wbindgen_externrefs.get(mem.getUint32(i, true)));
        }
        wasm.__wbindgen_export7(ptr, len);
        return result;
    }

    function getArrayU8FromWasm0(ptr, len) {
        ptr = ptr >>> 0;
        return getUint8ArrayMemory0().subarray(ptr / 1, ptr / 1 + len);
    }

    let cachedDataViewMemory0 = null;
    function getDataViewMemory0() {
        if (cachedDataViewMemory0 === null || cachedDataViewMemory0.buffer.detached === true || (cachedDataViewMemory0.buffer.detached === undefined && cachedDataViewMemory0.buffer !== wasm.memory.buffer)) {
            cachedDataViewMemory0 = new DataView(wasm.memory.buffer);
        }
        return cachedDataViewMemory0;
    }

    function getStringFromWasm0(ptr, len) {
        ptr = ptr >>> 0;
        return decodeText(getUint8ArrayMemory0().subarray(ptr, ptr + len));
    }

    let cachedUint8ArrayMemory0 = null;
    function getUint8ArrayMemory0() {
        if (cachedUint8ArrayMemory0 === null || cachedUint8ArrayMemory0.byteLength === 0) {
            cachedUint8ArrayMemory0 = new Uint8Array(wasm.memory.buffer);
        }
        return cachedUint8ArrayMemory0;
    }

    function handleError(f, args) {
        try {
            return f.apply(this, args);
        } catch (e) {
            const idx = addToExternrefTable0(e);
            wasm.__wbindgen_export4(idx);
        }
    }

    function isLikeNone(x) {
        return x === undefined || x === null;
    }

    function passArray8ToWasm0(arg, malloc) {
        const ptr = malloc(arg.length * 1, 1) >>> 0;
        getUint8ArrayMemory0().set(arg, ptr / 1);
        WASM_VECTOR_LEN = arg.length;
        return ptr;
    }

    function passArrayJsValueToWasm0(array, malloc) {
        const ptr = malloc(array.length * 4, 4) >>> 0;
        for (let i = 0; i < array.length; i++) {
            const add = addToExternrefTable0(array[i]);
            getDataViewMemory0().setUint32(ptr + 4 * i, add, true);
        }
        WASM_VECTOR_LEN = array.length;
        return ptr;
    }

    function passStringToWasm0(arg, malloc, realloc) {
        if (realloc === undefined) {
            const buf = cachedTextEncoder.encode(arg);
            const ptr = malloc(buf.length, 1) >>> 0;
            getUint8ArrayMemory0().subarray(ptr, ptr + buf.length).set(buf);
            WASM_VECTOR_LEN = buf.length;
            return ptr;
        }

        let len = arg.length;
        let ptr = malloc(len, 1) >>> 0;

        const mem = getUint8ArrayMemory0();

        let offset = 0;

        for (; offset < len; offset++) {
            const code = arg.charCodeAt(offset);
            if (code > 0x7F) break;
            mem[ptr + offset] = code;
        }
        if (offset !== len) {
            if (offset !== 0) {
                arg = arg.slice(offset);
            }
            ptr = realloc(ptr, len, len = offset + arg.length * 3, 1) >>> 0;
            const view = getUint8ArrayMemory0().subarray(ptr + offset, ptr + len);
            const ret = cachedTextEncoder.encodeInto(arg, view);

            offset += ret.written;
            ptr = realloc(ptr, len, offset, 1) >>> 0;
        }

        WASM_VECTOR_LEN = offset;
        return ptr;
    }

    function takeFromExternrefTable0(idx) {
        const value = wasm.__wbindgen_externrefs.get(idx);
        wasm.__wbindgen_export6(idx);
        return value;
    }

    let cachedTextDecoder = new TextDecoder('utf-8', { ignoreBOM: true });
    cachedTextDecoder.decode();
    function decodeText(bytes) {
        return cachedTextDecoder.decode(bytes);
    }

    const cachedTextEncoder = new TextEncoder();

    if (!('encodeInto' in cachedTextEncoder)) {
        cachedTextEncoder.encodeInto = function (arg, view) {
            const buf = cachedTextEncoder.encode(arg);
            view.set(buf);
            return {
                read: arg.length,
                written: buf.length
            };
        };
    }

    let WASM_VECTOR_LEN = 0;

    let wasmModule, wasmInstance, wasm;
    function __wbg_finalize_init(instance, module) {
        wasmInstance = instance;
        wasm = instance.exports;
        wasmModule = module;
        cachedDataViewMemory0 = null;
        cachedUint8ArrayMemory0 = null;
        wasm.__wbindgen_start();
        return wasm;
    }

    async function __wbg_load(module, imports) {
        if (typeof Response === 'function' && module instanceof Response) {
            if (!module.ok) {
                throw new Error(`failed to fetch Wasm: ${module.status} ${module.statusText} fetching '${module.url}'`);
            }

            if (typeof WebAssembly.instantiateStreaming === 'function') {
                try {
                    return await WebAssembly.instantiateStreaming(module, imports);
                } catch (e) {
                    const validResponse = expectedResponseType(module.type);

                    if (validResponse && module.headers.get('Content-Type') !== 'application/wasm') {
                        console.warn("`WebAssembly.instantiateStreaming` failed because your server does not serve Wasm with `application/wasm` MIME type. Falling back to `WebAssembly.instantiate` which is slower. Original error:\n", e);

                    } else { throw e; }
                }
            }

            const bytes = await module.arrayBuffer();
            return await WebAssembly.instantiate(bytes, imports);
        } else {
            const instance = await WebAssembly.instantiate(module, imports);

            if (instance instanceof WebAssembly.Instance) {
                return { instance, module };
            } else {
                return instance;
            }
        }

        function expectedResponseType(type) {
            switch (type) {
                case 'basic': case 'cors': case 'default': return true;
            }
            return false;
        }
    }

    function initSync(module) {
        if (wasm !== undefined) return wasm;


        if (module !== undefined) {
            if (Object.getPrototypeOf(module) === Object.prototype) {
                ({module} = module)
            } else {
                console.warn('using deprecated parameters for `initSync()`; pass a single object instead')
            }
        }

        const imports = __wbg_get_imports();
        if (!(module instanceof WebAssembly.Module)) {
            module = new WebAssembly.Module(module);
        }
        const instance = new WebAssembly.Instance(module, imports);
        return __wbg_finalize_init(instance, module);
    }

    let __wbg_init_promise;
    async function __wbg_init(module_or_path) {
        if (wasm !== undefined) return wasm;
        if (__wbg_init_promise === undefined) {
            __wbg_init_promise = __wbg_init_once(module_or_path);
            __wbg_init_promise.catch(() => { __wbg_init_promise = undefined; });
        }
        return __wbg_init_promise;
    }

    async function __wbg_init_once(module_or_path) {

        if (module_or_path !== undefined) {
            if (Object.getPrototypeOf(module_or_path) === Object.prototype) {
                ({module_or_path} = module_or_path)
            } else {
                console.warn('using deprecated parameters for the initialization function; pass a single object instead')
            }
        }

        if (module_or_path === undefined && script_src !== undefined) {
            module_or_path = script_src.replace(/\.js$/, "_bg.wasm");
        }
        const imports = __wbg_get_imports();

        if (typeof module_or_path === 'string' || (typeof Request === 'function' && module_or_path instanceof Request) || (typeof URL === 'function' && module_or_path instanceof URL)) {
            module_or_path = fetch(module_or_path);
        }

        const { instance, module } = await __wbg_load(await module_or_path, imports);

        return __wbg_finalize_init(instance, module);
    }

    return Object.assign(__wbg_init, { initSync }, exports);
})({ __proto__: null });
