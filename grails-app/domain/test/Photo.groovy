package test

class Photo extends AbstractImage {

    String url

    static constraints = {
        url(nullable: false)
    }

    static searchable = {
        name fielddata: true
        url index: true
    }


    public String toString() {
        return "Photo{" +
                "id=" + id +
                ",url='" + url + '\'' +
                '}';
    }

    static mapping = {
        autoImport(false)
    }
}
