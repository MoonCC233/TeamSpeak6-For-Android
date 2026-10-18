/**
 * A permission, named by id or by name.
 *
 * The server accepts either — `permid=…` or `permsid=…` — and every command
 * that takes a permission takes exactly one of the two. Two optional
 * parameters let a caller send both or neither; this sends one.
 */

import type { PermissionID } from "./command/ids.js";
import type { PermissionName } from "./permission-name.js";

/**
 * A permission, by its numeric id or by its name.
 *
 * Ids are assigned by the server build and can differ between versions; a
 * name, such as `"i_client_talk_power"`, is the portable choice, an id the
 * compact one. Build one with {@link permissionByID} or
 * {@link permissionByName}.
 */
export type Permission = { readonly id: PermissionID } | { readonly name: string };

/** Name a permission by its numeric id; it is sent as `permid`. */
export function permissionByID(id: PermissionID): Permission {
  return { id };
}

/**
 * Name a permission by its name, such as
 * `PermissionName.ClientTalkPower` (`"i_client_talk_power"`); it is sent as
 * `permsid`. A name the {@link PermissionName} table lacks is accepted too.
 */
export function permissionByName(name: PermissionName | (string & {})): Permission {
  return { name };
}
