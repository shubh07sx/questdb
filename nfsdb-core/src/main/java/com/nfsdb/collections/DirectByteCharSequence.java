/*
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
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
 */

package com.nfsdb.collections;

import com.nfsdb.utils.Unsafe;

public class DirectByteCharSequence extends AbstractCharSequence {
    private long lo;
    private long hi;

    public void init(long lo, long hi) {
        this.lo = lo;
        this.hi = hi;
    }

    @Override
    public int length() {
        return (int) (hi - lo);
    }

    @Override
    public char charAt(int index) {
        return (char) Unsafe.getUnsafe().getByte(lo + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        DirectByteCharSequence seq = new DirectByteCharSequence();
        seq.lo = this.lo + start;
        seq.hi = this.lo + end;
        return seq;
    }

    public void lshift(long delta) {
        this.lo -= delta;
        this.hi -= delta;
    }
}
