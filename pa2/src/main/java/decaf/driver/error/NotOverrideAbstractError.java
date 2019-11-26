package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：'zig' is not abstract and does not override all abstract methods<br>
 * PA2
 */
public class NotOverrideAbstractError extends DecafError {

    private String name;

    public NotOverrideAbstractError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return "'" + name + "' is not abstract and does not override all abstract methods";
    }

}
