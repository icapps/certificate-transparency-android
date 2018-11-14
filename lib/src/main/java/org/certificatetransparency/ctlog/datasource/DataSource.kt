/*
 * Copyright 2018 Babylon Healthcare Services Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Derived from https://github.com/appmattus/layercache/
 */

package org.certificatetransparency.ctlog.datasource

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * A standard cache which stores and retrieves data
 */
interface DataSource<Value : Any> : CoroutineScope {
    /**
     * Return the value associated with this data source or null if not present
     */
    suspend fun get(): Value?

    /**
     * Save the value to this data source
     */
    suspend fun set(value: Value)

    /**
     * Compose two data sources. Try to fetch from the first data source and, failing that, request the data from the second data source.
     * After being retrieved from the second data source, the data is saved to the first data source for future retrieval.
     */
    fun compose(b: DataSource<Value>): DataSource<Value> {
        return object : DataSource<Value> {
            override suspend fun get(): Value? {
                return this@DataSource.get() ?: let {
                    b.get()?.apply { this@DataSource.set(this) }
                }
            }

            override suspend fun set(value: Value) {
                awaitAll(async { this@DataSource.set(value) }, async { b.set(value) })
            }

            override val coroutineContext = this@DataSource.coroutineContext + b.coroutineContext
        }
    }

    /**
     * Compose two data sources. Try to fetch from the first data source and, failing that, request the data from the second data source.
     * After being retrieved from the second data source, the data is saved to the first data source for future retrieval.
     */
    operator fun plus(b: DataSource<Value>) = compose(b)

    /**
     * If a get call is already in flight then this ensures the original request is returned
     */
    fun reuseInflight(): DataSource<Value> {
        return object : DataSource<Value> {
            private var job: Deferred<Value?>? = null

            override suspend fun get(): Value? {
                return (job ?: async { this@DataSource.get() }.apply {
                    job = this

                    launch {
                        this@apply.join()
                        job = null
                    }
                }).await()
            }

            override suspend fun set(value: Value) = this@DataSource.set(value)

            override val coroutineContext = this@DataSource.coroutineContext
        }
    }

    /**
     * Map values from one type to another. As this is a one way transform calling set on the resulting cache is no-op
     */
    fun <MappedValue : Any> oneWayTransform(transform: (Value) -> MappedValue): DataSource<MappedValue> {
        return object : DataSource<MappedValue> {
            override suspend fun get(): MappedValue? {
                return this@DataSource.get()?.run(transform)
            }

            // No-op
            override suspend fun set(value: MappedValue) = Unit

            override val coroutineContext = this@DataSource.coroutineContext
        }
    }
}
