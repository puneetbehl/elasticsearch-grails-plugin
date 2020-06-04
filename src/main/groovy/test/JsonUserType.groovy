package test

import grails.converters.JSON
import org.grails.web.json.JSONElement
import org.grails.web.json.JSONObject
import org.hibernate.HibernateException
import org.hibernate.engine.spi.SessionImplementor
import org.hibernate.usertype.UserType

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

class JsonUserType implements UserType {
    int[] sqlTypes() {
        [Types.LONGVARCHAR] as int[]
    }

    Class returnedClass() {
        JSONObject
    }

    boolean equals(a, b) {
        a == b
    }

    int hashCode(a) {
        a.hashCode()
    }

    @Override
    Object nullSafeGet(ResultSet rs, String[] names, SessionImplementor session, Object owner) throws
            HibernateException, SQLException {
        String str = rs.getString(names[0])
        str ? JSON.parse(str) : null
    }

    @Override
    void nullSafeSet(PreparedStatement st, Object value, int index, SessionImplementor session) throws
            HibernateException, SQLException {
        if (value == null) {
            st.setNull(index, sqlTypes()[0])
        } else {
            JSONElement json = value as JSONElement
            st.setString(index, json.toString())
        }
    }

    Object deepCopy(Object o) {
        o
    }

    Serializable disassemble(Object o) {
        o
    }

    Object assemble(Serializable cached, Object owner) {
        cached
    }

    Object replace(Object original, Object target, Object owner) {
        original
    }

    boolean isMutable() {
        false
    }
}
