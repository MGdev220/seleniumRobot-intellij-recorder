package io.github.bhecquet.seleniumRobot.recorder;

import java.util.Objects;

public class FrameInfo {
    private String id;
    private String selector; // "By.id("testFrame")"

    public FrameInfo() {
    }

    public FrameInfo(String id, String selector) {
        this.id = id;
        this.selector = selector;
    }

    public String getId() {
        return id;
    }

    public String getSelector() {
        return selector;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setSelector(String selector) {
        this.selector = selector;
    }


    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FrameInfo)) return false;
        FrameInfo other = (FrameInfo) o;
        return Objects.equals(id, other.id) &&
                Objects.equals(selector, other.selector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, selector);
    }

}

