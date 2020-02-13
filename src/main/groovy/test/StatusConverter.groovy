package test

import java.beans.PropertyEditorSupport

class StatusConverter extends PropertyEditorSupport {

    @Override
    void setAsText(String text) throws IllegalArgumentException {
        Converter.Status value = null
        Converter.Status.values().find {
            if (it.value == text) {
                value = it
                return true
            }
            false
        }

        setValue(value)
    }
}

