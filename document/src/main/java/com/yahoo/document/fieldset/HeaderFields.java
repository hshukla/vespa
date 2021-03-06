// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.fieldset;

import com.yahoo.document.Field;

/**
 * @deprecated do not use
 */
@Deprecated // TODO: Remove on Vespa 8
public class HeaderFields implements FieldSet {

    @Override
    public boolean contains(FieldSet o) {
        if (o instanceof HeaderFields || o instanceof DocIdOnly || o instanceof NoFields) {
            return true;
        }

        if (o instanceof Field) {
            return ((Field)o).isHeader();
        }

        if (o instanceof FieldCollection) {
            FieldCollection c = (FieldCollection)o;
            for (Field f : c) {
                if (!f.isHeader()) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    @Override
    public FieldSet clone() throws CloneNotSupportedException {
        return new HeaderFields();
    }

}
