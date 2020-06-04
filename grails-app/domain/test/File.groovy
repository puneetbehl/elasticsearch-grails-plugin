package test

class File {
    String filename
    String contentType
    String attachment // base64 encoded file contents

    static searchable = {
        attachment attachment: true
    }

    static constraints = {
        filename nullable: false
        contentType nullable: true
        attachment nullable: true // file can be empty?
    }
}
